#!/usr/bin/env python3
"""Common, fail-closed entrypoint for chapter 7/8 program-level verification."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import csv
import hashlib
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import shlex
import shutil
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent))
from cvw_common import ROOT, Collector, dump, manifest as hash_artifacts, reproduction, process_exit_results, publish_reports

SMOKE_ARCH = ['addi-01', 'jalr-01', 'lw-align-01']
SMOKE_PRIV = ['EX02', 'PR01', 'CS01', 'WFWAKE']


def stage_plan(suite, profile):
    if suite == 'all':
        return (['privileged', 'scala', 'privileged_coverage', 'rtl', 'validation', 'cvw', 'diff', 'python']
                if profile == 'full' else ['privileged', 'scala', 'privileged_coverage', 'validation', 'diff'])
    return {'pipeline': ['scala', 'rtl'] if profile == 'full' else ['scala'],
            'arch': ['validation', 'diff'], 'coremark': ['validation'],
            'privileged': ['privileged', 'scala', 'privileged_coverage'], 'cvw': ['cvw'], 'diff': ['diff']}[suite]


def accepted(rows):
    # A link alone is never evidence that a failed run was resolved. Historical
    # attempts remain visible; this invocation is accepted only if all rows pass.
    return bool(rows) and all(r.get('status') == 'pass' or
                             (r.get('status') == 'not_applicable' and bool(r.get('reason')))
                             for r in rows)


def count_results(rows):
    counted = [r for r in rows if r.get('counted', True) and r.get('status') != 'not_applicable']
    return dict(independentCases=len({(r.get('suite'), r.get('name')) for r in counted}),
                configurationPairs=len({(r.get('suite'), r.get('name'), r.get('config', 'default')) for r in counted}),
                runs=len(counted), statuses=dict(Counter(r['status'] for r in rows)))


def case_entry(suite, name, config='default', **kw):
    return dict(id=name, suite=suite, chapter=kw.pop('chapter', 7), config=config,
                elf=kw.pop('elf', None), image=kw.pop('image', None),
                reference=kw.pop('reference', None), check=kw.pop('check', 'ordered-events'),
                completion=kw.pop('completion', 'adapter-defined explicit completion; observe eight cycles'),
                cycleLimit=kw.pop('cycleLimit', None), coveragePoints=kw.pop('coveragePoints', []), **kw)


def reconcile_results(rows, expected):
    result = [dict(r) for r in rows]
    fields = ('suite', 'name', 'config', 'seed', 'repeat')
    for item in expected:
        matches = [r for r in result if all(r.get(f) == item.get(f) for f in fields)]
        if not matches:
            result.append(dict(item, status='not_executed', reason='required case evidence missing'))
        elif len(matches) > 1:
            for r in matches:
                r.update(status='build_platform_error', reason='duplicate required case evidence')
    return result


def attach_commands(rows, directory):
    commands = {}
    for path in directory.rglob('commands.jsonl'):
        for line in path.read_text().splitlines():
            record = json.loads(line)
            commands[record.get('log')] = record
    result = []
    for row in rows:
        row = dict(row)
        record = commands.get(row.get('log'))
        if record:
            row.setdefault('exit', record['exit'])
            row.setdefault('command', record['argv'])
            row['reproduceOriginal'] = ('cd ' + shlex.quote(record.get('cwd', str(ROOT))) +
                                        ' && ' + shlex.join(record['argv']))
        result.append(row)
    # Group the reset records belonging to one process. A first successful reset
    # remains valid if a later reset failed; two passing resets cannot hide rc=1.
    for log, record in commands.items():
        indices = [i for i, row in enumerate(result) if row.get('log') == log and
                   'negative' not in row.get('suite', '')]
        checked = process_exit_results([result[i] for i in indices], record['exit'])
        for i, row in zip(indices, checked):
            result[i] = row
    return result


def sources():
    result = {}
    for base in ('src', 'scripts', 'programs', 'project'):
        for path in sorted((ROOT/base).rglob('*')):
            if path.is_file() and not {'__pycache__', 'target'} & set(path.parts) and path.suffix not in ('.pyc', '.class'):
                result[str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
    for name in ('Makefile', 'build.sbt'):
        result[name] = hashlib.sha256((ROOT/name).read_bytes()).hexdigest()
    return result


class Verification:
    def __init__(self, directory, suite, profile):
        self.directory, self.suite, self.profile = directory, suite, profile
        self.collector = Collector(directory)
        self.rows, self.cases, self.expected = [], [], []
        self.env = dict(os.environ)
        self.env.setdefault('SBT', './scripts/sbt-local.sh')
        self.env.setdefault('VERILATOR', 'verilator')
        self.env['JAVA_TOOL_OPTIONS'] = self.env.get('JAVA_TOOL_OPTIONS', '') + ' -XX:ActiveProcessorCount=4 -Xmx3g'
        self.env['CHAPTER8_RUN_DIR'] = str(directory/'privileged')
        self.env['VALIDATION_BUILD_DIR'] = str(directory/'validation')
        for name in ('CHAPTER8_CASE_FILTER', 'CHAPTER8_CONFIGS', 'CHAPTER8_CASE_IDS'):
            self.env.pop(name, None)
        self.env['CHAPTER8_COVERAGE'] = '1'
        self.seeds = [1] if profile == 'smoke' else [1, 17, 2026]
        self.env['VALIDATION_SEEDS'] = ' '.join(map(str, self.seeds))
        self.env['CHAPTER8_SEEDS'] = self.env['VALIDATION_SEEDS']
        self.env['CVW_SEEDS'] = self.env['VALIDATION_SEEDS']

    def command(self, key, argv, timeout=7200):
        record = self.collector.command(key, argv, timeout=timeout, env=self.env)
        self.rows.append(dict(chapter=7, suite='entrypoint', name=key, counted=False,
                              status='pass' if record['exit'] == 0 else 'build_platform_error',
                              reason=record['error'] or ('' if record['exit'] == 0 else 'adapter command failed'),
                              exit=record['exit'], log=record['log'], command=record['argv'],
                              reproduce=reproduction(record['argv'])))
        return record

    def import_rows(self, path, key=None):
        if path.exists():
            data = json.loads(path.read_text())
            rows = data[key] if key else data
            if not isinstance(rows, list):
                raise ValueError(f'{path}: result rows must be a list')
            self.rows.extend(rows)
            return data
        self.rows.append(dict(chapter=7, suite='evidence', name=str(path.relative_to(self.directory)),
                              status='not_executed', reason='adapter did not produce required results'))
        return None

    def privileged(self):
        if self.profile == 'smoke':
            self.env['CHAPTER8_CONFIGS'] = 'default'
            self.env['CHAPTER8_CASE_IDS'] = ' '.join(SMOKE_PRIV)
        self.command('privileged', [sys.executable, ROOT/'scripts/run/test-chapter8-rtl.py'])
        self.import_rows(self.directory/'privileged/results.json')
        path = self.directory/'privileged/manifest.tsv'
        details = self.directory/'privileged/cases.json'
        detail = {r['key']: r for r in json.loads(details.read_text())} if details.exists() else {}
        if path.exists():
            for row in csv.DictReader(path.open(), delimiter='\t'):
                if self.profile == 'smoke' and (row['config'] != 'default' or row['id'] not in SMOKE_PRIV):
                    continue
                info = detail.get(row['config']+'-'+row['id'], {})
                self.cases.append(case_entry('existing-rtl', row['id'], row['config'], chapter=8,
                    image=row['image'], reference=row['events'], cycleLimit=int(row['limit']),
                    book=info.get('book'), coveragePoints=[], check='ordered-store-events',
                    completion='final expected event and eight-cycle no-store observation'))
                self.expected.extend(dict(suite='existing-rtl', name=row['id'], config=row['config'],
                                          chapter=8, seed=s, repeat=r) for s in self.seeds for r in (0, 1))

    def scala(self):
        junit = self.directory/'junit'
        junit.mkdir()
        if self.suite == 'all' and self.profile == 'full':
            selection = 'test'
        elif self.suite == 'privileged':
            selection = 'testOnly riscvpipeline.Chapter8TestPlanSpec'
        elif self.profile == 'smoke':
            selection = 'testOnly riscvpipeline.PipelineSmokeSpec'
            if self.suite == 'all':
                selection += ' riscvpipeline.Chapter8TestPlanSpec'
        else:
            selection = 'testOnly riscvpipeline.Pipeline* riscvpipeline.CpuAcceptanceSpec riscvpipeline.ieu.* riscvpipeline.ifu.* riscvpipeline.lsu.*'
        if self.profile == 'smoke' and self.suite in ('all', 'privileged'):
            self.env['CHAPTER8_CASE_FILTER'] = 'default-(' + '|'.join(SMOKE_PRIV) + ')'
        else:
            self.env.pop('CHAPTER8_CASE_FILTER', None)
        self.command('scala', [self.env['SBT'],
            f'set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "{junit}")', selection])
        found = 0
        for path in sorted(junit.glob('*.xml')):
            suite = ET.parse(path).getroot()
            for case in suite.findall('testcase'):
                found += 1
                bad = case.find('failure')
                if bad is None:
                    bad = case.find('error')
                name = suite.attrib['name']+': '+case.attrib['name']
                chapter = 8 if '.privileged.' in name or 'Chapter8' in name else 7
                self.rows.append(dict(chapter=chapter, suite='scala', name=name, config='scala',
                    status='cpu_failure' if bad is not None else 'not_executed' if case.find('skipped') is not None else 'pass',
                    reason=bad.attrib.get('message', '') if bad is not None else '', log=str(path)))
                self.cases.append(case_entry('scala', name, 'scala', chapter=chapter,
                    check='ScalaTest assertions', reference='src/test/scala', completion='test framework result'))
        if not found:
            self.rows.append(dict(suite='scala', name='required-test-results', status='not_executed', reason='no fresh JUnit test cases'))

    def privileged_coverage(self):
        output = self.directory/'privileged/functional-coverage.json'
        record = self.command('privileged-functional-coverage', [sys.executable,
            ROOT/'scripts/lib/privileged_coverage.py', '--directory', self.directory/'privileged',
            '--profile', self.profile, '--output', output])
        data = json.loads(output.read_text()) if output.exists() else {}
        passed = record['exit'] == 0 and data.get('passed') is True
        self.rows.append(dict(chapter=8, suite='functional-coverage', name='privileged-required-bins',
            status='pass' if passed else 'cpu_failure',
            reason='' if passed else 'required dynamic privileged evidence missing or failed',
            log=record['log'], evidence=str(output)))
        for case in self.cases:
            if case['suite'] == 'existing-rtl':
                bins = data.get('configurations', {}).get(case['config'], {}).get('bins', {})
                key = case['config'] + '-' + case['id']
                case['coveragePoints'] = [name for name, value in bins.items() if value.get('first', {}).get('case') == key]
                case['coverageRegistry'] = str(output)

    def rtl(self):
        source = self.directory/'production-rtl/source'
        art = self.directory/'production-rtl'
        configs = [('book', 64, 64, 'programs/riscvtest.memfile', 0),
                   ('expanded', 128, 128, 'programs/rv32-configtest.memfile', '0x100'),
                   ('acceptance', 128, 64, 'target/acceptance-images/rv32-acceptance.memfile', 0),
                   ('acceptance-expanded', 256, 128, 'target/acceptance-images/rv32-acceptance-expanded.memfile', '0x100')]
        self.command('production-generate', [self.env['SBT'], *[
            f'runMain riscvpipeline.generators.GenerateRiscvPipeline {i} {d} {source/name} {image} {reset}'
            for name, i, d, image, reset in configs]])
        self.env.update(RTL_TEST_DIR=str(art), RTL_TEST_SOURCE_DIR=str(source), RTL_TEST_SEEDS=' '.join(map(str,self.seeds)))
        record = self.command('production-rtl', ['bash', ROOT/'scripts/run/test-rtl.sh'])
        output = Path(record['log']).read_text()
        for name, _, _, image, _ in configs:
            self.cases.append(case_entry('rtl', name, name, image=image, reference=str(art/(name+'.events')),
                                         check='ordered-memory-requests', cycleLimit=300))
            for seed in self.seeds:
                ok = f'PASS {art}/{name}.events seed={seed} ' in output
                for repeat in (0, 1):
                    self.rows.append(dict(chapter=7, suite='rtl', name=name, config=name, seed=seed, repeat=repeat,
                        status='pass' if ok else 'cpu_failure' if f'CPU_FAILURE {name} seed={seed}' in output else 'not_executed',
                        reason='' if ok else 'production RTL evidence missing or failed', log=record['log']))

    def validation(self):
        suite = 'coremark' if self.suite == 'coremark' else 'all' if self.profile == 'full' and self.suite == 'all' else 'arch'
        args = [sys.executable, ROOT/'scripts/build/build-validation.py', suite]
        if self.profile == 'smoke' and suite == 'arch':
            args += ['--tests', *SMOKE_ARCH]
        build = self.command('validation-build', args)
        art = self.directory/'validation'
        path = art/(suite+'.json')
        if not path.exists():
            self.rows.append(dict(suite='validation', name=suite, status='not_executed', reason='program manifest unavailable after build failure'))
            return
        data = json.loads(path.read_text())
        for p in data['programs']:
            family = 'coremark' if p['name'].startswith('coremark') else 'arch'
            self.cases.append(case_entry(family, p['name'], chapter=7,
                elf=str(art/p['name']/'program.elf'), image=p['image'], reference=p['reference'],
                check='Spike/store-reconstructed/CPU-load-readback signature', cycleLimit=p['cycleLimit'],
                completion='indexed LW readback, length, completion store, eight observation cycles'))
            self.expected.extend(dict(chapter=7, suite=family, name=p['name'], seed=s) for s in self.seeds)
        generated = self.command('validation-generate', [self.env['SBT'],
            f'runMain riscvpipeline.generators.GenerateRiscvPipeline 524288 32768 {art}/rtl-source {art}/program.memfile 0x80000000'])
        self.env['VALIDATION_RTL_FILE'] = str(art/'rtl-source/RiscvPipeline.v')
        if generated['exit'] == 0:
            self.command('validation-run', ['bash', ROOT/'scripts/run/test-validation.sh', suite])
        results = self.import_rows(art/(suite+'-results.json'), 'results')
        if results:
            self.rows.extend(dict(chapter=7, suite='validation-checker-negative', name=r['name'], **{k:v for k,v in r.items() if k!='name'})
                             for r in results.get('negativeChecks', []))

    def cvw(self):
        art = self.directory/'cvw'
        self.command('cvw', [sys.executable, ROOT/'scripts/run/run-chapter8-cvw.py', '--output', art])
        self.import_rows(art/'results.json')
        path = art/'programs/manifest.json'
        if path.exists():
            for p in json.loads(path.read_text()):
                self.cases.append(case_entry('cvw', p['name'], p['config'], chapter=8,
                    elf=str(Path(p.get('directory',''))/'program.elf') if p.get('directory') else None,
                    image=str(Path(p.get('directory',''))/'program.hex') if p.get('directory') else None,
                    reference=str(Path(p['directory'])/'reference.signature') if p.get('directory') else p.get('reference'),
                    referenceSource='CVW native reference signatures and documented adaptation',
                    check='full CPU LW readback and ordered protocol', cycleLimit=1000000,
                    applicability=p['status'], reason=p.get('reason','')))

    def diff(self):
        argv = [sys.executable, ROOT/'scripts/run/run-diff.py', '--output', self.directory/'diff', '--profile', self.profile]
        if (self.directory/'validation').exists() and self.suite in ('all', 'arch'):
            argv += ['--validation-dir', self.directory/'validation']
        self.command('diff', argv)
        self.import_rows(self.directory/'diff/results.json')
        path = self.directory/'diff/manifest.json'
        if path.exists():
            raw = json.loads(path.read_text())
            functional = self.directory/'diff/functional-coverage.json'
            bins = json.loads(functional.read_text()).get('bins', {}) if functional.exists() else {}
            for p in raw if isinstance(raw, list) else raw.get('programs', []):
                name = p.get('id', p.get('name', 'unknown'))
                self.cases.append(case_entry('diff', name, 'rv32i', **{
                    k:v for k,v in p.items() if k not in ('id','name','suite','config','coveragePoints')},
                    coveragePoints=[key for key, value in bins.items() if value.get('first', {}).get('case') == name],
                    hardwareConfiguration=raw.get('config') if isinstance(raw, dict) else None))

    def python(self):
        self.command('python', [sys.executable, '-m', 'unittest', 'discover', '-s', 'src/test/python', '-v'])

    def finish(self, before):
        after = sources()
        dump(self.directory/'source-after.json', after)
        if after != before:
            self.rows.append(dict(suite='integrity', name='source-stability', status='build_platform_error', reason='source changed during run'))
        self.rows = attach_commands(reconcile_results(self.rows, self.expected), self.directory)
        for r in self.rows:
            r.setdefault('reproduce', reproduction([sys.executable, 'scripts/run/verify.py', '--suite', self.suite, '--profile', self.profile]))
        dump(self.directory/'manifest.json', dict(schemaVersion=1, suite=self.suite, profile=self.profile,
            seeds=self.seeds, cases=self.cases, requiredRuns=self.expected))
        dump(self.directory/'results.json', self.rows)
        counts = count_results(self.rows)
        passed = accepted(self.rows)
        dump(self.directory/'summary.json', dict(passed=passed, **counts))
        with (self.directory/'results.csv').open('w') as stream:
            writer = csv.DictWriter(stream, fieldnames=['chapter','suite','name','config','seed','repeat','status','reason','exit','log','reproduce'], extrasaction='ignore')
            writer.writeheader()
            writer.writerows(self.rows)
        failures = [r for r in self.rows if r['status'] not in ('pass','not_applicable')]
        report = f'# 第七、八章本轮验证\n\n运行：`{self.directory.name}`；套件 `{self.suite}`；配置 `{self.profile}`；结论：**{"PASS" if passed else "FAIL"}**。\n\n'
        report += f'独立用例 {counts["independentCases"]}，用例/配置组合 {counts["configurationPairs"]}，运行记录 {counts["runs"]}。入口命令不加入独立用例统计。\n\n'
        report += f'状态：`{json.dumps(counts["statuses"], ensure_ascii=False)}`。本目录只使用本轮产物，历史结果不参与判定。\n\n'
        report += '逐例证据见 results.json/csv；原始退出码及参数见各层 commands.jsonl；统一清单见 manifest.json；工具版本见 metadata/；源码快照及哈希见 source/ 和 source-before.json。差分、覆盖详见 diff/。\n\n'
        report += '## 失败或未执行\n\n' + ('无。\n' if not failures else ''.join(f'- {r["suite"]}/{r["name"]}: {r["status"]}; {r.get("reason", "")}\n' for r in failures))
        report += '\n代码覆盖率与功能覆盖率必须读取各采集器的实测数据；用例数、程序包含某指令及通过状态不能替代功能命中。完整特权差分不在本轮能力范围。\n'
        (self.directory/'report.md').write_text(report)
        hash_artifacts(self.directory)
        return passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['all','pipeline','arch','privileged','cvw','coremark','diff'], default='all')
    parser.add_argument('--profile', choices=['smoke','full'], default='smoke')
    parser.add_argument('--output', type=Path, help='new output directory; existing directories are rejected')
    parser.add_argument('--archive', action='store_true', help='force all/full; publish Markdown to docs and keep evidence under target')
    parser.add_argument('--plan', action='store_true', help='print selected stages without executing')
    args = parser.parse_args()
    if args.archive:
        args.suite, args.profile = 'all', 'full'
    if args.plan:
        print(json.dumps(dict(suite=args.suite, profile=args.profile, stages=stage_plan(args.suite,args.profile)), indent=2))
        return 0
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    directory = (args.output or ROOT/'target/verification/runs'/stamp).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    latest = ROOT/'target/verification/latest-run.txt'
    latest.parent.mkdir(parents=True, exist_ok=True)
    latest.write_text(str(directory)+'\n')
    run = Verification(directory, args.suite, args.profile)
    before = sources()
    dump(directory/'source-before.json', before)
    for relative in before:
        dest = directory/'source'/relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/relative, dest)
    for name, argv in [('git-head',['git','rev-parse','HEAD']), ('git-status',['git','status','--porcelain=v1']),
                       ('verilator',[run.env['VERILATOR'],'--version']), ('spike',[run.env.get('SPIKE','spike'),'--help']),
                       ('gcc',[run.env.get('RISCV_TOOL_PREFIX','riscv64-unknown-elf-')+'gcc','--version']),
                       ('java',['java','-version']), ('python',[sys.executable,'--version']),
                       ('sbt',[run.env['SBT'],'show sbtVersion'])]:
        run.collector.command('metadata/'+name, argv, timeout=180, env=run.env)
    for stage in stage_plan(args.suite,args.profile):
        try:
            getattr(run, stage)()
        except Exception as exc:
            run.rows.append(dict(suite='orchestration', name=stage, status='build_platform_error', reason=repr(exc)))
    passed = run.finish(before)
    if args.archive:
        archive = ROOT/'docs/reports/verification'/directory.name
        publish_reports(directory, archive, 'make report-verification SBT=./scripts/sbt-local.sh')
        print(f'Reports: {archive}; evidence: {directory}', flush=True)
    print(f'{"PASS" if passed else "FAIL"}: {directory}/report.md', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
