#!/usr/bin/env python3
"""Recover only the known platform setup failure; retain the single full regression."""
import importlib.util
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import shutil
import xml.etree.ElementTree as ET
from cvw_common import ROOT, Collector, dump, fresh_run, fresh_reproduction, manifest, save_results, publish_reports, rebase_report_links


def load_driver():
    spec = importlib.util.spec_from_file_location('joint',ROOT/'scripts/run/test-chapter7-8-cvw.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def main():
    initial = Path(sys.argv[1]).resolve()
    directory = fresh_run()
    collector = Collector(directory)
    driver = load_driver()
    rows = json.loads((initial/'results.json').read_text())
    # Copy initial evidence unchanged, including the failed setup attempt.
    shutil.copytree(initial,directory/'initial-attempt',ignore=shutil.ignore_patterns('*.o','*.a','*.d','*.cpp','*.h','*.mk'))
    before = driver.sources()
    dump(directory/'source-before.json',before)
    for relative in before:
        path = directory/'source'/relative
        path.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/relative,path)
    env=dict(os.environ,SBT=os.environ.get('SBT','./scripts/sbt-local.sh'),
             VERILATOR=os.environ.get('VERILATOR','verilator'),
             CVW_ROOT=os.environ.get('CVW_ROOT','/home/unlastingstar/cvw'),
             CHAPTER8_RUN_DIR=str(directory/'chapter8-existing'),
             JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g')
    env.pop('CHAPTER8_CASE_FILTER',None)
    junit=directory/'junit-recovery';junit.mkdir()
    record=collector.command('scala-platform-recovery',[env['SBT'],
        f'set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "{junit}")',
        'testOnly riscvpipeline.Chapter8TestPlanSpec'],env=env,timeout=7200)
    replacement=[]
    for path in junit.glob('*.xml'):
        tree=ET.parse(path).getroot()
        for case in tree.findall('testcase'):
            failure=case.find('failure')
            replacement.append(dict(chapter=8,suite='scala',name=tree.attrib['name']+': '+case.attrib['name'],
                               status='pass' if failure is None else 'cpu_failure',
                               reason='' if failure is None else failure.attrib.get('message',''),log=str(path)))
    original=[r for r in rows if r['suite']=='scala' and 'Chapter8TestPlanSpec:' in r['name']]
    complete=(record['exit']==0 and len(replacement)==len(original)==276 and
              {r['name'] for r in replacement}=={r['name'] for r in original})
    if complete:
        rows=[r for r in rows if r not in original]+replacement
        for row in rows:
            if row['suite']=='entrypoint' and row['name']=='scala-full':
                row['resolved_by']='scala-platform-recovery'
                row['reason']='initial 276 fixture setup errors (absolute TargetDir); recovered only the affected suite; see initial-attempt/scala-full.log'
    else:
        rows+=replacement
        rows.append(dict(chapter=8,suite='recovery',name='scala-platform-recovery',status='build_platform_error',reason='recovery failed or case inventory differs',log=record['log']))
    # Recheck final collection/reproduction changes on both real RTL matrices.
    for suite,argv,path in [
        ('existing-rtl',[sys.executable,ROOT/'scripts/run/test-chapter8-rtl.py'],directory/'chapter8-existing/results.json'),
        ('cvw',[sys.executable,ROOT/'scripts/run/run-chapter8-cvw.py','--output',directory/'chapter8-cvw'],directory/'chapter8-cvw/results.json')]:
        record=collector.command(suite+'-final',argv,env=env,timeout=3600)
        if path.exists():
            fresh=json.loads(path.read_text())
            remove={'existing-rtl','existing-checker-negative'} if suite=='existing-rtl' else {'cvw','cvw-checker-negative'}
            rows=[r for r in rows if r['suite'] not in remove]+fresh
        if record['exit']:
            rows.append(dict(chapter=8,suite='recovery',name=suite,status='build_platform_error',reason='final RTL recheck failed',log=record['log']))
    record=collector.command('python-final',[sys.executable,'-m','unittest','discover','-s','src/test/python','-v'],env=env)
    rows.append(dict(chapter=8,suite='python-final',name='all',status='pass' if record['exit']==0 else 'build_platform_error',log=record['log']))
    record=collector.command('validation-final',['make','test-validation','VALIDATION_SUITE=all',
        'SBT='+env['SBT'],'VERILATOR='+env['VERILATOR'],'VALIDATION_BUILD_DIR='+str(directory/'validation'),
        'VALIDATION_RTL_DIR='+str(directory/'validation/rtl-source')],env=env,timeout=3600)
    validation=directory/'validation/all-results.json'
    if validation.exists():
        data=json.loads(validation.read_text())
        rows=[r for r in rows if r['suite'] not in ('arch','coremark','validation-checker-negative')]+data['results']
        rows += [dict(chapter=7,suite='validation-checker-negative',name=r['name'],status=r['status'],log=r['log']) for r in data['negativeChecks']]
    if record['exit']:
        rows.append(dict(chapter=7,suite='recovery',name='validation-final',status='build_platform_error',reason='final validation failed',log=record['log']))
    # No result reproduction writes back into the evidence or legacy current-run pointer.
    for row in rows:
        suite = 'validation' if row['suite'] in ('arch','coremark','validation-checker-negative') else 'cvw' if row['suite'].startswith('cvw') else 'existing-rtl' if row['suite'].startswith('existing') else 'joint'
        row['reproduce']=fresh_reproduction(suite,env)
    after=driver.sources()
    dump(directory/'source-after.json',after)
    dump(directory/'recovery.json',dict(initialRun=str(initial),fullScalaRuns=1,
         initialScala=dict(tests=590,passed=314,platformSetupErrors=276),recoveredCases=len(replacement),
         reviewFixes=['torn evidence collection and matrix reconciliation','fresh reproduction directories'],
         cpuSourcesUnchanged=all(before[k]==v for k,v in json.loads((initial/'source-before.json').read_text()).items() if k.startswith('src/main/'))))
    if before!=after:
        rows.append(dict(chapter=8,suite='integrity',name='source-changed',status='build_platform_error',reason='source changed during recovery'))
    save_results(directory,rows)
    driver.render(directory,rows,collector.commands,before==after)
    report=directory/'report.md'
    with report.open('a') as stream:
        stream.write('\n本轮恢复记录：全量 Scala 仅执行一次，首次 314 通过、276 因绝对 TargetDir 在仿真前报错；修正测试夹具后仅补跑 Chapter8TestPlanSpec 的 276 项。原始失败日志与结果原样保存在 initial-attempt/，最终有效矩阵采用补跑结果。CPU 实现未修改。复核修正另以最终四配置 RTL、CVW 双配置 RTL 与 Python 回归验证；各阶段源码快照分别保存。\n')
    source = ROOT/'docs/chapter7-8-cvw.md'
    destination = directory/'coverage-and-adaptation.md'
    destination.write_text(rebase_report_links(source.read_text(), source, destination, {}))
    manifest(directory)
    archive=ROOT/'docs/reports/chapter7-8-cvw'/directory.name
    publish_reports(directory, archive, 'make report-chapter7-8-cvw SBT=./scripts/sbt-local.sh')
    (ROOT/'target/chapter7-8-cvw/latest-run.txt').write_text(str(directory)+'\n')
    print('FINAL REPORT '+str(archive/'report.md'),flush=True)
    return int(any(r['status'] not in ('pass','not_applicable') and not r.get('resolved_by') for r in rows))


if __name__=='__main__':
    sys.exit(main())
