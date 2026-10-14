#!/usr/bin/env python3
"""One fresh joint run; immutable evidence and reports also survive failed stages."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import re
import shutil
import xml.etree.ElementTree as ET
from cvw_common import ROOT, Collector, dump, fresh_run, manifest, reproduction, save_results, fresh_reproduction

from report_catalog import register_report


def sources():
    paths = [ROOT/'Makefile', ROOT/'build.sbt']
    for folder in ('src', 'scripts', 'programs', 'project'):
        paths += [p for p in (ROOT/folder).rglob('*') if p.is_file() and
                  '__pycache__' not in p.parts and 'target' not in p.parts and p.suffix not in ('.pyc', '.class')]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}


def render(directory, rows, commands, source_unchanged):
    counts = Counter(r['status'] for r in rows if not r.get('resolved_by'))
    for chapter in (7, 8):
        selected = [r for r in rows if r['chapter'] == chapter]
        save_results(directory/f'chapter{chapter}', selected)
        groups = {}
        for suite in sorted({r['suite'] for r in selected}):
            groups[suite] = dict(Counter(r['status'] for r in selected if r['suite'] == suite))
        dump(directory/f'chapter{chapter}/summary.json', groups)
        text = f'# 第{chapter}章本轮结果\n\n|测试组|状态计数|\n|---|---|\n'
        text += ''.join(f'|{suite}|{json.dumps(value, ensure_ascii=False)}|\n' for suite, value in groups.items())
        failures = [r for r in selected if r['status'] not in ('pass', 'not_applicable') and not r.get('resolved_by')]
        text += '\n## 失败和未执行\n\n' + ('无。\n' if not failures else ''.join(
            f'- {r["suite"]}/{r["name"]} ({r.get("config", "")}, seed={r.get("seed", "")}, repeat={r.get("repeat", "")}): {r["status"]}; {r.get("reason", "")}\n' for r in failures))
        if chapter == 7:
            topics = {'模块': ('riscvpipeline.ieu.', 'riscvpipeline.ifu.', 'riscvpipeline.lsu.'),
                      '流水线冒险': ('hazard',), '前递': ('forward',), '停顿': ('stall', 'interlock'),
                      '冲刷': ('flush', 'wrong-path'), '访存': ('memory', 'load', 'store', 'dtim', 'lsu'),
                      '跳转': ('jump', 'jal', 'branch'), '复位': ('reset', 'restart')}
            classified = {label: [r for r in selected if r['suite']=='scala' and
                          any(word.lower() in r['name'].lower() for word in words)] for label,words in topics.items()}
            dump(directory/'chapter7/topics.json',classified)
            text += '\n## 第七章功能分组\n\n同一测试可以涉及多个分组，以下计数不相加为独立总数。\n\n|分组|测试数|状态|\n|---|---|---|\n'
            text += ''.join(f'|{label}|{len(entries)}|{dict(Counter(r["status"] for r in entries))}|\n' for label,entries in classified.items())
        (directory/f'chapter{chapter}/report.md').write_text(text)
    failures = [r for r in rows if r['status'] not in ('pass', 'not_applicable') and not r.get('resolved_by')]
    text = '# 第七、八章 CPU 测试总报告\n\n'
    text += f'UTC 运行目录：`{directory.name}`。本轮结论：' + ('全部适用项通过。' if not failures else f'存在 {len(failures)} 条失败或未执行记录；详见逐例结果。') + '\n\n'
    text += f'状态计数：`{json.dumps(dict(counts), ensure_ascii=False)}`。源码前后校验：{"一致" if source_unchanged else "不一致，结果不能作为同一源码验收"}。\n\n'
    text += ('本报告只使用本目录本轮命令产物，历史报告不参与通过判定。全量 Scala 回归仅执行一次；'
             '独立测试计数以 Scala 测试名称、RTL 的配置/程序/种子/复位轮次分别呈现。'
             '`test-acceptance` 为重复入口核验，不加入独立用例计数。结果行不是互不重叠的功能覆盖数量。\n\n'
             '第七章：2 MiB IROM、128 KiB DTIM、复位 0x80000000；39 个架构程序上限 100 万周期；'
             'CoreMark 性能/验证两模式各三个种子，2000 字节、10 次迭代、上限 3000 万周期。'
             '逐字比较 Spike 签名，同时检查官方 CRC、错误数和迭代数；报告仿真周期，不计算官方得分。\n\n'
             '第八章：18 个 CVW RV32 原始样例，默认与 Sstc/HPM 配置；默认 WALLY-trap-01 显式不适用；'
             '其他 10 个上游样例逐项列于 chapter8-cvw/programs/coverage.md。64 KiB IROM/DTIM、复位 0；'
             '每配置每程序种子 1/17/2026，各同实例复位重跑两次，上限 100 万周期。'
             'CPU 真实 SW 初始化所有数据/查找表/哨兵，真实 LW 读回全部 1024 字签名及前后哨兵；'
             '核对使用长度、完成标记和后续八拍副作用。另执行既有四配置 RTL 与 Python 检查器回归。\n\n'
             '适配说明：异常地址零改为 0x10000；ELF 符号重定位断点及非对齐地址；Bare medeleg 合法掩码为 0x3ff；'
             'CLINT/PLIC/GPIO 换成存储标记驱动同步引脚；默认 STIP 为软件置位，扩展使用 Sstc。'
             '异步处理器按 xepc 恢复；结束 ecall 保留原签名，委派到 S 的结束通过额外 M 软件中断进入平台收尾，'
             '该收尾中断不写测试签名。初始化 Sstc 比较器为全一。所有适配补丁与独立预期推导均随程序保存。\n\n')
    core = [r for r in rows if r['suite'] == 'coremark']
    if core:
        text += '|CoreMark 模式|种子|状态|完成周期|测量区间周期|\n|---|---|---|---|---|\n'
        text += ''.join(f'|{r["name"]}|{r.get("seed", "")}|{r["status"]}|{r.get("cycles", "")}|{r.get("benchmarkCycles", "")}|\n' for r in core) + '\n'
    text += '逐例结果：results.json/csv；章节汇总：chapter7/、chapter8/；命令与退出码：各目录 commands.jsonl；复现：reproduce.sh；完整性：SHA256SUMS。\n'
    (directory/'report.md').write_text(text)
    lines = ['#!/usr/bin/env bash', '# Failed/unexecuted entries; preserve command order and original image paths.', 'set -u']
    for row in failures:
        lines += ['# '+row['suite']+'/'+row['name']+': '+row.get('reason','').replace('\n',' '), row.get('reproduce', '# '+row.get('log','No command: shared dependency did not build'))]
    (directory/'reproduce.sh').write_text('\n'.join(lines)+'\n')


def main():
    directory = fresh_run()
    (ROOT/'target/chapter7-8-cvw/latest-run.txt').write_text(str(directory)+'\n')
    collector = Collector(directory)
    rows = []
    before = sources()
    dump(directory/'source-before.json', before)
    for relative in before:
        dest = directory/'source'/relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT/relative, dest)
    env = dict(os.environ)
    env.setdefault('SBT', './scripts/sbt-local.sh')
    env.setdefault('VERILATOR', 'verilator')
    env['JAVA_TOOL_OPTIONS'] = env.get('JAVA_TOOL_OPTIONS', '') + ' -XX:ActiveProcessorCount=4 -Xmx3g'
    env['CHAPTER8_RUN_DIR'] = str(directory/'chapter8-existing')
    env.pop('CHAPTER8_CASE_FILTER', None)
    env['VALIDATION_SEEDS'] = '1 17 2026'
    env['VALIDATION_BUILD_DIR'] = str(directory/'validation')
    def command(key, argv, timeout=7200):
        record = collector.command(key, argv, timeout=timeout, env=env)
        rows.append(dict(chapter=7 if key.startswith(('scala', 'acceptance', 'chapter7', 'validation')) else 8,
                         suite='entrypoint', name=key, status='pass' if record['exit'] == 0 else 'build_platform_error',
                         reason=record['error'] or ('' if record['exit'] == 0 else 'entrypoint failed; inspect detailed case evidence'),
                         log=record['log'], reproduce=fresh_reproduction('joint', env), counted=False))
        save_results(directory, rows)
        return record
    try:
        for key, argv in [('repository-status', ['git','status','--porcelain=v1']), ('repository-head',['git','rev-parse','HEAD']),
                          ('sbt-version',[env['SBT'],'show sbtVersion']), ('verilator-version',[env['VERILATOR'],'--version']),
                          ('spike-version',[env.get('SPIKE','spike'),'--help']),
                          ('compiler-version',[env.get('RISCV_TOOL_PREFIX','riscv64-unknown-elf-')+'gcc','--version']),
                          ('python-version',[sys.executable,'--version'])]:
            collector.command('metadata/'+key, argv, timeout=180, env=env)
        # Build the old checker first so ALL Python tests use this run's binaries.
        command('chapter8-existing', ['make','test-chapter8-rtl','SBT='+env['SBT'],'VERILATOR='+env['VERILATOR']])
        old = directory/'chapter8-existing/results.json'
        if old.exists():
            rows += json.loads(old.read_text())
        junit = directory/'junit'
        junit.mkdir()
        scala = command('scala-full', [env['SBT'], f'set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-u", "{junit}")', 'test'])
        for xml in junit.glob('*.xml'):
            tree = ET.parse(xml).getroot()
            suite = tree.attrib['name']
            chapter = 8 if '.privileged.' in suite or 'Chapter8' in suite else 7
            for case in tree.findall('testcase'):
                failed = case.find('failure') if case.find('failure') is not None else case.find('error')
                status = ('build_platform_error' if failed is not None and ('is not a relative path' in failed.attrib.get('message', '') or 'class java.' in failed.attrib.get('type', '')) else 'cpu_failure') if failed is not None else 'not_executed' if case.find('skipped') is not None else 'pass'
                rows.append(dict(chapter=chapter, suite='scala', name=suite+': '+case.attrib['name'], status=status,
                                 reason=failed.attrib.get('message','') if failed is not None else '', log=str(xml)))
        command('acceptance-duplicate-entry', ['make','test-acceptance','SBT='+env['SBT']])
        rtl_run = command('chapter7-rtl', ['make','test-rtl','SBT='+env['SBT'],'VERILATOR='+env['VERILATOR']])
        rtl_text = Path(rtl_run['log']).read_text()
        for name in ('book','expanded','acceptance','acceptance-expanded'):
            for seed in (1,17,2026):
                passed = f'PASS target/rtl-test/{name}.events seed={seed} ' in rtl_text
                cpu = f'CPU_FAILURE {name} seed={seed}' in rtl_text
                for repeat in (0,1):
                    rows.append(dict(chapter=7,suite='rtl',name=name,seed=seed,repeat=repeat,
                                     status='pass' if passed else 'cpu_failure' if cpu else 'not_executed',
                                     reason='' if passed else 'see fresh generation/build/simulator log',log=rtl_run['log'],
                                     reproduce=reproduction(rtl_run['argv'])))
        if (ROOT/'target/rtl-test').exists():
            shutil.copytree(ROOT/'target/rtl-test', directory/'chapter7-rtl-evidence', ignore=shutil.ignore_patterns('*.o','*.a','*.d','*.cpp','*.h','*.mk'))
        command('validation-all', ['make','test-validation','VALIDATION_SUITE=all','SBT='+env['SBT'],
                        'VERILATOR='+env['VERILATOR'], 'VALIDATION_BUILD_DIR='+env['VALIDATION_BUILD_DIR'],
                        'VALIDATION_RTL_DIR='+str(directory/'validation/rtl-source')])
        result_path = directory/'validation/all-results.json'
        if result_path.exists():
            evidence = json.loads(result_path.read_text())
            rows += evidence['results']
            for r in evidence['negativeChecks']:
                rows.append(dict(chapter=7,suite='validation-checker-negative',name=r['name'],status=r['status'],log=r['log']))
        else:
            upstream = Path(env.get('CVW_ROOT', '/home/unlastingstar/cvw'))/'addins/riscv-arch-test'
            import subprocess
            try:
                names = subprocess.check_output(['git','-C',str(upstream),'ls-tree','-r','--name-only',
                   'c8c813af55ea79acb97fe832c5737cc4539af3ee','riscv-test-suite/rv32i_m/I/src'],text=True).splitlines()
                names = [Path(n).stem for n in names if n.endswith('.S')]
            except Exception:
                names = ['all-38-rv32i-programs']
            for name in names+['misalign1-jalr-01','coremark-performance','coremark-validation']:
                for seed in (1,17,2026):
                    rows.append(dict(chapter=7,suite='coremark' if name.startswith('coremark') else 'arch',name=name,seed=seed,status='not_executed',reason='shared validation build failed; see validation-all.log'))
        command('chapter8-cvw', [sys.executable, ROOT/'scripts/run/run-chapter8-cvw.py','--output',directory/'chapter8-cvw'])
        new = directory/'chapter8-cvw/results.json'
        if new.exists():
            rows += json.loads(new.read_text())
        else:
            for config in ('default','sstc-hpm'):
                rows.append(dict(chapter=8,suite='cvw',name='all-18-upstream-cases',config=config,status='not_executed',reason='shared CVW adapter build failed'))
        command('python-regression', [sys.executable,'-m','unittest','discover','-s','src/test/python','-v'])
    except Exception as exc:
        rows.append(dict(chapter=8,suite='orchestration',name='exception',status='build_platform_error',reason=repr(exc)))
    finally:
        after = sources()
        dump(directory/'source-after.json', after)
        if before != after:
            rows.append(dict(chapter=8,suite='integrity',name='sources-changed',status='build_platform_error',reason='sources changed during the run'))
        save_results(directory, rows)
        render(directory, rows, collector.commands, before == after)
        manifest(directory)
        passed = not any(r['status'] not in ('pass', 'not_applicable') and not r.get('resolved_by') for r in rows)
        register_report(directory, 'chapter7-8-cvw', 'make report-chapter7-8-cvw SBT=./scripts/sbt-local.sh', root=ROOT, status='pass' if passed else 'fail')
        print('REPORT '+str(directory/'report.md'), flush=True)
    return int(any(r['status'] not in ('pass','not_applicable') for r in rows))


if __name__ == '__main__':
    sys.exit(main())
