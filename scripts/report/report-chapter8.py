#!/usr/bin/env python3
"""Validate complete fresh evidence, archive it, and render the Chinese §8.3 report."""
import csv
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import os
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / "target/cache/python")
os.environ["PYTHONPYCACHEPREFIX"] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from cvw_common import publish_report

ROOT = Path(__file__).resolve().parents[2]
BASE_ART = ROOT / 'target/chapter8-testplan'
POINTER = BASE_ART / 'current-run.txt'
ART = ROOT / POINTER.read_text().strip() if POINTER.exists() else BASE_ART
ARCHIVE = ART
REPORT = ROOT / 'docs/chapter8/16-testplan-report.md'
BASE = '5b1804fbbf0eb03636e19502e77e20340698e0c3'

def validate_run(expected, run):
    if not run['pass'] or run['failure']:
        raise ValueError('failed execution cannot be reported as passing')
    if not 0 < run['cycles'] <= 10000:
        raise ValueError('invalid cycle bound')
    times = [e['cycle'] for e in run['actual']]
    if any(n < 0 or n >= run['cycles'] for n in times) or any(a >= b for a, b in zip(times, times[1:])):
        raise ValueError('invalid or unordered event cycles')
    actual = [{k: e[k] for k in ('address', 'data', 'mask')} for e in run['actual']]
    if actual != expected:
        raise ValueError('signature mismatch or missing/extra store in evidence')
    if not run['actual'] or run['cycles'] - run['actual'][-1]['cycle'] != 9:
        raise ValueError('completion must include the commit edge plus eight observation cycles')
    if int(actual[-1]['address']) != 512 or int(actual[-1]['data']) != 90:
        raise ValueError('missing completion marker')

def capture(argv):
    result = subprocess.run(argv, cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f'{argv}: {result.stderr}')
    return result.stdout.strip()

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_paths():
    return sorted(set(ROOT.glob('src/main/scala/**/*.scala')) |
                  set(ROOT.glob('src/test/scala/**/*.scala')) |
                  set(ROOT.glob('src/test/cpp/*.cpp')) |
                  set(ROOT.glob('src/test/python/*.py')) |
                  {p for p in ROOT.glob('scripts/**/*') if p.is_file() and '__pycache__' not in p.parts} |
                  {ROOT / 'Makefile', ROOT / 'build.sbt'})

def fingerprints():
    return {str(p.relative_to(ROOT)): digest(p) for p in source_paths() if p.is_file()}

def validate_freshness(started, before, current, paths):
    if before != current:
        raise ValueError('verification sources changed since run start')
    for path in paths:
        if not path.is_file() or path.stat().st_mtime < started:
            raise ValueError(f'missing or stale evidence: {path}')

def validate_junit(paths, expected_suites, total, shared_names=None):
    trees = [ET.parse(path).getroot() for path in paths]
    if {t.attrib['name'] for t in trees} != expected_suites or len(trees) != len(expected_suites):
        raise ValueError('missing/duplicate JUnit suite inventory')
    actual_total = 0
    for tree in trees:
        if any(int(tree.attrib.get(field, '0')) != 0 for field in ('failures', 'errors', 'skipped')):
            raise ValueError('failed or skipped JUnit suite')
        entries = tree.findall('testcase')
        if int(tree.attrib['tests']) != len(entries):
            raise ValueError('incomplete JUnit testcase inventory')
        names = [entry.attrib['name'] for entry in entries]
        if len(set(names)) != len(names):
            raise ValueError('duplicate JUnit testcase')
        for entry in entries:
            if any(entry.find(field) is not None for field in ('failure', 'error', 'skipped')):
                raise ValueError('failed/skipped JUnit testcase')
        if tree.attrib['name'] == 'riscvpipeline.Chapter8TestPlanSpec' and set(names) != shared_names:
            raise ValueError('filtered or incomplete shared Scala suite')
        actual_total += len(entries)
    if actual_total != total:
        raise ValueError('JUnit count differs from full regression')
    return trees

def begin():
    global ART
    BASE_ART.mkdir(parents=True, exist_ok=True)
    ART = BASE_ART / 'runs' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    ART.mkdir(parents=True, exist_ok=False)
    POINTER.write_text(str(ART.relative_to(ROOT)) + '\n')
    if (BASE_ART / 'baseline.log').is_file():
        shutil.copyfile(BASE_ART / 'baseline.log', ART / 'baseline.log')
    for path in BASE_ART.glob('*.log'):
        if path.name != 'baseline.log':
            shutil.copyfile(path, ART / ('history-' + path.name))
    if (BASE_ART / 'first-evidence').exists():
        shutil.copytree(BASE_ART / 'first-evidence', ART / 'first-evidence')
    session = {'started': time.time(), 'started_utc': datetime.now(timezone.utc).isoformat(),
               'source_sha256': fingerprints(),
               'expected_suites': sorted('riscvpipeline.' + '.'.join(p.relative_to(ROOT / 'src/test/scala/riscvpipeline').with_suffix('').parts)
                                        for p in (ROOT / 'src/test/scala/riscvpipeline').rglob('*Spec.scala'))}
    (ART / 'session.json').write_text(json.dumps(session, indent=2) + '\n')
    print('Started fresh chapter8 evidence session:', session['started_utc'])

def main():
    session = json.loads((ART / 'session.json').read_text())
    evidence = [ART / name for name in ('cases.json', 'manifest.tsv', 'regression.log',
                'chapter7-rtl.log', 'negative-green.log', 'python.log', 'export-rtl.log')]
    cases = json.loads((ART / 'cases.json').read_text())
    runs = []
    case_runs = {}
    for case in cases:
        key = case['key']
        records = []
        for repeat in (0, 1):
            path = ART / f'scala/{key}-r{repeat}.json'
            evidence.append(path)
            value = json.loads(path.read_text())
            validate_run(case['expected'], value)
            if value['key'] != key or value['repeat'] != repeat:
                raise ValueError(f'wrong Scala identity: {path}')
            records.append({'key': key, 'layer': 'scala', 'seed': 1, **value,
                            'evidence': str(path.relative_to(ART))})
        for seed in (1, 17, 2026):
            path = ART / f'rtl-results/{key}-s{seed}.json'
            evidence.append(path)
            value = json.loads(path.read_text())
            if not value['pass'] or value['seed'] != seed or len(value['runs']) != 2:
                raise ValueError(f'failed/incomplete RTL reset runs: {path}')
            for repeat, run in enumerate(value['runs']):
                validate_run(case['expected'], run)
                if run['repeat'] != repeat:
                    raise ValueError('wrong reset repeat')
                records.append({'key': key, 'layer': 'rtl', 'seed': seed, **run,
                                'evidence': str(path.relative_to(ART))})
        case_runs[key] = records
        runs.extend(records)
    regression = (ART / 'regression.log').read_text()
    if 'All tests passed.' not in regression or '[error]' in regression:
        raise ValueError('full Scala regression is missing or failed')
    chapter7 = (ART / 'chapter7-rtl.log').read_text()
    if len(re.findall(r'^PASS ', chapter7, re.MULTILINE)) != 12:
        raise ValueError('chapter7 RTL must pass all four configurations and three seeds')
    negative = []
    for name in ('signature', 'missing', 'extra', 'timeout', 'observation-window'):
        evidence.append(ART / f'negative/{name}.json')
        value = json.loads((ART / f'negative/{name}.json').read_text())
        if value['pass'] or not value['failure']:
            raise ValueError(f'negative checker did not fail: {name}')
        negative.append({'name': name, 'failure': value['failure'], 'returncode': 1})
    if 'OK' not in (ART / 'negative-green.log').read_text():
        raise ValueError('negative checks did not complete')
    paths = source_paths()
    sources = fingerprints()
    junit_paths = sorted((ART / 'junit').glob('TEST-*.xml'))
    evidence.extend(junit_paths)
    for name in ('default', 'minimal', 'sstc-hpm', 'reset100'):
        evidence += [ART / f'{name}-{kind}.log' for kind in ('generate', 'lint', 'build')]
    for case in cases:
        evidence += [ART / f'cases/{case["key"]}.{extension}' for extension in ('hex', 'events')]
    validate_freshness(session['started'], session['source_sha256'], sources, evidence)
    count = re.search(r'Total number of tests run: (\d+)', regression)
    if not count:
        raise ValueError('full regression test count is missing')
    shared_names = {f'Chapter 8.3 shared architectural test plan should {c["key"]}: {c["title"]}' for c in cases}
    validate_junit(junit_paths, set(session['expected_suites']), int(count.group(1)), shared_names)
    cvw = Path('/home/unlastingstar/cvw')
    references = sorted((cvw / 'tests/wally-riscv-arch-test/riscv-test-suite/rv32i_m/privilege/src').glob('WALLY-*'))
    references += [cvw / 'docs/testplans/testplan.md', ROOT.parent / 'RISC-V System-on-Chip Design, Edition 1.pdf']
    meta = {'generated_utc': datetime.now(timezone.utc).isoformat(), 'baseline_commit': BASE,
            'head': capture(['git', 'rev-parse', 'HEAD']), 'git_status': capture(['git', 'status', '--short']),
            'cvw_commit': capture(['git', '-C', str(cvw), 'rev-parse', 'HEAD']),
            'verilator': capture(['verilator', '--version']), 'python': capture(['python3', '--version']),
            'compiler': capture(['g++', '--version']).splitlines()[0],
            'sbt': '1.10.7', 'scala': '2.13.14', 'chisel': '3.6.1', 'chiseltest': '0.6.2',
            'java': 'Eclipse Adoptium 17.0.20.1 (see baseline/regression log)',
            'session_started_utc': session['started_utc'], 'source_sha256': sources, 'reference_sha256': {str(p): digest(p) for p in references},
            'seeds': [1, 17, 2026], 'reset_runs': 2, 'cycle_limit': 10000,
            'configurations': {'default': [True, True, False, 0, 0],
                               'minimal': [False, False, False, 0, 0],
                               'sstc-hpm': [True, True, True, 3, 0],
                               'reset100': [True, True, False, 0, 256]},
            'memory_words': [2048, 2048], 'wfi_timeout': 24}
    ARCHIVE.mkdir(parents=True, exist_ok=True)
    # Copy only evidence, never binaries, mutable runtime program.hex, or tool caches.
    for pattern in ('*.log', '*.jsonl', 'session.json', 'manifest.tsv', 'cases.json', 'cases/*', 'scala/*.json',
                    'rtl-results/*', 'negative/*', 'first-evidence/*', 'rtl/*/lint.log',
                    'rtl/*/build.log', 'rtl/*/PrivilegedPipeline.v'):
        for path in ART.glob(pattern):
            if path.is_file():
                target = ARCHIVE / path.relative_to(ART)
                target.parent.mkdir(parents=True, exist_ok=True)
                if path.resolve() != target.resolve():
                    shutil.copyfile(path, target)
    test_records = []
    for path in sorted((ART / 'junit').glob('TEST-*.xml')):
        tree = ET.parse(path).getroot()
        if tree.attrib.get('failures', '0') != '0' or tree.attrib.get('errors', '0') != '0':
            raise ValueError(f'failed JUnit report: {path}')
        target = ARCHIVE / 'junit' / path.name
        target.parent.mkdir(exist_ok=True)
        if path.resolve() != target.resolve():
            shutil.copyfile(path, target)
        for test in tree.findall('testcase'):
            identity = tree.attrib['name'] + ':' + test.attrib['name']
            test_records.append({'id': 'UT-' + hashlib.sha256(identity.encode()).hexdigest()[:10],
                                 'suite': tree.attrib['name'], 'name': test.attrib['name'],
                                 'seconds': test.attrib.get('time'), 'pass': True,
                                 'evidence': str(target.relative_to(ARCHIVE))})
    (ARCHIVE / 'metadata.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
    (ARCHIVE / 'results.json').write_text(json.dumps(runs, ensure_ascii=False, indent=2) + '\n')
    (ARCHIVE / 'unit-tests.json').write_text(json.dumps(test_records, ensure_ascii=False, indent=2) + '\n')
    fields = ['key', 'layer', 'seed', 'repeat', 'cycles', 'pass', 'failure', 'evidence']
    with (ARCHIVE / 'results.csv').open('w') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction='ignore')
        writer.writeheader(); writer.writerows(runs)
    configs = Counter(c['config'] for c in cases)
    categories = Counter(c['category'] for c in cases)
    ids = {c['id'] for c in cases}
    applicability = {name: {'applicable': configs[name], 'not_applicable': sorted(ids -
                     {c['id'] for c in cases if c['config'] == name})} for name in configs}
    (ARCHIVE / 'applicability.json').write_text(json.dumps(applicability, ensure_ascii=False, indent=2) + '\n')
    count = re.search(r'Total number of tests run: (\d+)', regression)
    text = f'''# §8.3 TestPlan 实施与详尽报告

本轮以教材印刷页 360–366（PDF 页 395–401）、表 8.5–8.9、当前 Chisel RV32 核心与本地 CVW 特权源码为依据。
快照提交为 `{BASE}`。本轮共享整机用例 **{len(cases)} 个配置实例**，Scala 每例复位重跑 2 次，独立 RTL 每例 3 种种子各重跑 2 次；共核对 **{len(runs)} 次运行**的完整有序签名。
全量 Scala 回归 **{count.group(1) if count else 'unknown'} 项通过**，第七章 RTL 四组配置、三个种子共 12 次通过；最终失败、未执行均为 0。所有统计来自本轮实际产物。

## 1. 教材与CVW对照、平台适配

| 教材要求 | CVW对照 | 本轮检查与证据 |
| --- | --- | --- |
| 表8.5 ECALL/EBREAK/MRET/SRET | WALLY-trap-01/trap-s-01/trap-u-01 | EX00–EX11、PV01–PV06、IRRE；既有Chapter8AcceptanceSpec的嵌套委托/返回也全量复验 |
| 表8.5 WFI、表8.7 TW/TSR | WALLY-wfi-01/status-tw-01/trap-sret-01 | WF系列、WFWAKE、PV04；既有WFI/PrivDecode/PrivMode单元测试 |
| 表8.5 SFENCE.VMA | 教材尚未显式测试 | PV03：当前Bare实现不支持此指令，真实程序应产生cause=2 |
| 表8.6 CSR读改写、权限、只读、身份 | csr-permission-u/s-01/minfo-01/misa-01 | CS01–CS08及CSRAccess/CSR全量单元测试 |
| 表8.6 状态、委托、陷阱、向量、S视图 | mtvec-01/stvec-01/sie-01/trap-s-01 | CS03、IRVIEW、IR系列；CSRMachine/CSRSupervisor/CSRStatus |
| 表8.6 Counters原文尚未测试 | 本轮补充 | CT01/CTA/CTD/CTRET/CTCARRY/CTTIME/HPM1–5；CSRCounters |
| 表8.7 MBE/SBE/UBE/MPRV | WALLY-endianness-01 | EN系列及既有word/byte/half真实存储测试；CSRStatus/EndianSwap |
| 表8.8 11类非页异常 | WALLY-trap-01及U/S用例 | EX系列逐类检查EPC/cause/tval/status/pending与有序存储；PR01/PR02检查精确取消 |
| 表8.9 六类中断 | WALLY-trap-01、TEST-LIB-32.h | IR1/3/5/7/9/11的直接/向量、M处理/S委托，STIRQ补充Sstc产生STIP |
| 中断优先级、屏蔽、返回重入 | mie-01/status-mie-01/status-sie-01 | IRMASK/IRCROSS/IRPRI/IRRE，TrapSpec穷举全部非空来源子集与权限组合 |

CVW revision：`{meta['cvw_commit']}`。本轮读取了RV32特权测试程序和TEST-LIB-32.h，沿用其陷阱签名思想与场景语义；未直接运行CVW的外设程序，也不声称与Spike/Imperas逐指令等价。
CVW的CLINT/PLIC/GPIO触发机制在本平台分别适配为同域中断输入、MIP/SIP软件位和Sstc比较；IROM/DTIM窗口之外用0x2000产生访问故障，地址0在本平台合法。
本核不含C扩展，因此额外执行取指未对齐测试；页面、PMP/PMA及原子异常遵循已确认的范围排除。

中断优先级预期独立核对[官方特权规范Machine-Level ISA 1.13](https://docs.riscv.org/reference/isa/priv/machine.html)：MEI、MSI、MTI、SEI、SSI、STI。S类的软件中断先于定时中断。

## 2. 基线、版本、命令与计时口径

本轮以 session.json 中的源码指纹和完整测试套件清单为准；全量 JUnit 必须包含每个套件和全部共享场景。
历史基线提交为 `{BASE}`，历史测试数量不作为本轮通过证据。

工具：sbt {meta['sbt']}、Scala {meta['scala']}、Chisel {meta['chisel']}、chiseltest {meta['chiseltest']}、{meta['java']}；{meta['verilator']}；{meta['compiler']}；{meta['python']}。
报告生成UTC时间：`{meta['generated_utc']}`；sbt日志时间采用Asia/Shanghai（UTC+8）。仿真周期只计解除复位后的上升沿；包含完成标记提交沿及之后8拍，不包括复位拍：Scala为3拍，RTL为3–5拍。
RTL两次运行使用同一DUT对象；DTIM不随复位清空，因此每个fixture都初始化它随后读取的数据。
未指定物理频率，周期不是秒数或性能评分。工具命令耗时见[commands.jsonl](../reports/chapter8-testplan/commands.jsonl)。

```sh
./scripts/sbt-local.sh compile test                      # 快照后基线
python3 scripts/report/report-chapter8.py --begin              # 最终验证起点
make test-chapter8-plan SBT=./scripts/sbt-local.sh        # 共享Scala用例
make test-chapter8-rtl SBT=./scripts/sbt-local.sh         # 四配置lint/build/仿真/负向检查
chapter8_run=$(python3 scripts/report/report-chapter8.py --path)
env -u CHAPTER8_CASE_FILTER JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Xmx3g' ./scripts/sbt-local.sh \
  "set Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, \\"-u\\", \\"$chapter8_run/junit\\")" compile test \
  > "$chapter8_run/regression.log" 2>&1
make test-rtl SBT=./scripts/sbt-local.sh > "$chapter8_run/chapter7-rtl.log" 2>&1
python3 -m unittest discover -s src/test/python -v > "$chapter8_run/python.log" 2>&1
python3 scripts/report/report-chapter8.py                       # 验证证据、归档、生成本报告
# 一键重新测试并更新报告：
make report-chapter8 SBT=./scripts/sbt-local.sh
```

最终完整回归日志见[regression.log](../reports/chapter8-testplan/regression.log)，第七章见[chapter7-rtl.log](../reports/chapter8-testplan/chapter7-rtl.log)。
`scripts/run/test-chapter8-rtl.py`导出共享清单；每个配置用GeneratePrivilegedPipeline生成生产RTL，执行`--lint-only -Wall`和`--cc --exe --build`。
仅关闭既有DECLFILENAME/UNUSEDSIGNAL警告分类，lint/build的全部输出归档于`rtl/<配置>/`。
实际逐个命令含绝对路径、返回码和耗时，记录于commands.jsonl；源码与CVW/教材SHA-256见[metadata.json](../reports/chapter8-testplan/metadata.json)。
最终源码HEAD和工作区状态在metadata.json中记录；新增测试/驱动/报告属于该快照上的可审阅工作区变更，原始快照与本轮变更分离。

## 3. 四配置与检查协议

| 配置 | S | 大端 | Sstc | 活动HPM | 复位地址 | IROM/DTIM字数 | WFI阈值 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| default | 开 | 开 | 关 | 0 | 0 | 2048/2048 | 24 |
| minimal | 关 | 关 | 关 | 0 | 0 | 2048/2048 | 24 |
| sstc-hpm | 开 | 开 | 开 | 3 | 0 | 2048/2048 | 24 |
| reset100 | 开 | 开 | 关 | 0 | 0x100 | 2048/2048 | 24 |

所有适用RTL用例执行种子1/17/2026，每个种子在同一对象上复位重跑。种子控制未初始化状态随机化、复位长度与外部输入到达延迟；不产生随机的期望值。
程序在地址600写入激励标记，驱动在随后9+(seed mod 3)拍、下降沿后稳定阶段设置输入。此时WFI保持，唤醒后固定后继指令作为IRQ的EPC。
处理程序向地址604写应答标记，驱动在该标记提交沿后撤销输入。TIME始终为明确的64位值，STIRQ从0变为0x100000020，应答后回到0。

存储在上升沿提交之前采样，检查地址、原始WriteData和ByteMask的全部有序事件（包括初始数据、陷阱日志、激励/应答和完成标记）。
地址512写0x5a完成；签名区从1024起每条陷阱五个字依次记录EPC、cause、tval、status、pending。
S陷阱记录SSTATUS/SIP，M陷阱记录MSTATUS/MIP；因此S签名不包含机器状态位。
非法、缺失、额外或错序存储均失败，每轮上限10000拍。完成后继续观察8拍，以检查迟到副作用。
Scala只通过输入驱动程序，不直接修改内部寄存器；RTL驱动只连接生产顶层端口。

异常触发：EX00执行JAL到PC+2（EPC=源PC，tval=目标）；EX01跳转0x2000（EPC/tval均为故障取指地址）；EX02执行0xffffffff（tval=指令）；EX03执行EBREAK（tval=PC）。
EX04/06执行地址2的LW/SW（tval=2）；EX05/07执行地址0x2000的LW/SW（tval=0x2000）；EX08/09/11通过真实MRET进入U/S/M后执行ECALL（tval=0）。
所有异常夹具在故障前写入老指令结果，保留目的寄存器哨兵，并紧跟年轻poison store；处理程序跳过故障/poison后存入寄存器结果，从完整存储序列验证精确提交。
CTRET以5条正常指令、14条处理程序指令及2条恢复/抑制指令推导instret=21，故障与被取消指令不计；CTCARRY以明确半字写和3条退休指令推导instret高半8、低半1。
HPM1–5分别选择退休、加载、存储、LoadStallD、StoreStallD来源；受控主体有7次退休、2次加载、2次存储、2次加载结果依赖、2次相邻存储/加载冲突，停止后以NOP隔离年轻CSR冒险。

共享清单与独立预期见[manifest.tsv](../reports/chapter8-testplan/manifest.tsv)、[cases.json](../reports/chapter8-testplan/cases.json)。
每个用例的2048字镜像、events以及完整实际签名均归档。`results.json/csv`保留每层、每种子、每次复位的周期和结果。

## 4. 汇总：适用项通过率

| 配置 | 适用配置实例 | Scala运行通过/失败 | RTL运行通过/失败 | 按ID并集统计不适用 | 未执行 |
| --- | --- | --- | --- | --- | --- |
'''
    for config, n in configs.items():
        text += f'| {config} | {n} | {n*2}/0 | {n*6}/0 | {len(applicability[config]["not_applicable"])} | 0 |\n'
    text += '\n| 功能 | 适用配置实例 | Scala运行 | RTL运行 | 失败 | 未执行 |\n| --- | --- | --- | --- | --- | --- |\n'
    for category, n in categories.items():
        text += f'| {category} | {n} | {n*2} | {n*6} | 0 | 0 |\n'
    text += f'''
适用项运行通过率为100%；不适用ID来自配置专属用例（包括minimal的非法能力探测），不计入分母。逐ID适用性见[applicability.json](../reports/chapter8-testplan/applicability.json)。
验证层级：叶模块/组合边界和内部提交由本轮全量Scala复验，整机由共享程序Scala {len(cases)*2}次和独立RTL {len(cases)*6}次检查。RTL通过率不包含生成/lint，也不包含检查器故意失败。

| 明确不适用能力 | 原因 |
| --- | --- |
| 取指/加载/存储页异常 | 无MMU，Bare模式 |
| PMP保护和可配置PMA | 无PMP/总线属性管理；TIM窗口边界访问故障仍已检查 |
| 浮点CSR、FS/SD与FPU异常 | 无浮点单元，相关位固定零 |
| 原子扩展及AMO异常 | 无A扩展 |
| CLINT、PLIC、GPIO硬件 | 本核无外设；同域输入和CSR触发覆盖中断路径 |
| RV64 SXL/UXL、向量VS/XS | 固定RV32，无向量/附加扩展 |

## 5. 逐用例预期、实际、周期与陷阱签名

每行是一个稳定用例编号在一个适用配置上的实例。预期写事件数量包含完成标记；实际各次运行都逐项匹配且签名差异为0。
周期列为Scala两次运行的范围、RTL六次运行的范围。每行链接的events为独立预期，RTL链接含两次复位的实际完整签名（种子1）；种子17/2026及Scala记录见results.json。
陷阱列依序列出 `(epc,cause,tval,status,pending)` 的十六进制值。

| 配置/编号 | 行为、教材表、CVW对照 | 预期→实际写数/差异 | Scala拍 | RTL拍 | 陷阱记录 | 证据 |
| --- | --- | --- | --- | --- | --- | --- |
'''
    for case in cases:
        records = case_runs[case['key']]
        sc = [r['cycles'] for r in records if r['layer'] == 'scala']
        rt = [r['cycles'] for r in records if r['layer'] == 'rtl']
        trap_words = [int(e['data']) for e in case['expected'] if 1024 <= int(e['address']) < 1280]
        traps = '; '.join('(' + ','.join(f'{n:x}' for n in trap_words[i:i+5]) + ')'
                          for i in range(0, len(trap_words), 5)) or '无'
        key = case['key']
        cvw_name = case['cvw'].replace('|', '/')
        text += f'| {key} | {case["title"]}；表{case["book"]}；{cvw_name} | {len(case["expected"])}→相同 / 0 | {min(sc)}–{max(sc)} | {min(rt)}–{max(rt)} | `{traps}` | [预期](../reports/chapter8-testplan/cases/{key}.events) / [实际](../reports/chapter8-testplan/rtl-results/{key}-s1.json) |\n'
    text += '''
## 6. 既有测试复验与覆盖边界

全量Scala的JUnit记录逐条归档至`junit/`；每项按suite+name生成稳定UT哈希编号，完整清单见[unit-tests.json](../reports/chapter8-testplan/unit-tests.json)。
既有Chapter8AcceptanceSpec补充：分支/JAL/JALR三类取指未对齐、嵌套U→S→M异常委托、年轻跳转/故障取消、WFI老W提交与年轻前递、FENCE/FENCE.I、大小端字节/半字真实存储。
CSRAccessSpec检查六种操作的所有抑制规则与权限矩阵；TrapSpec检查异常两两优先级、64种中断来源子集、委托与模式；CSRStatus/PrivMode检查返回的MPRV与栈位；CSRCounters检查写入优先于增长、抑制、权限、高低半与事件编号合法化。
JUnit通过[ScalaTest官方-u报告参数](https://www.scalatest.org/user_guide/using_scalatest_with_sbt)输出到本轮独立目录。这些检查均在本轮重新执行；与共享用例重复的行为不额外增加配置实例通过率。

本轮是定向功能验证，不是指令集认证、形式证明或代码覆盖率收敛。独立RTL检查器独立实现时序/事件检查，但共用手写程序与期望，仍存在共同误解规范的风险。
所有有副作用存储都可观测，最终寄存器状态只有程序显式存入签名者可由RTL确认；不存在逐指令参考模型比对。
外部中断只验证同步输入协议，未验证异步CDC、电气时序或外设仲裁。种子改变初始状态/等待延迟，未覆盖任意时刻的所有中断交错；叶模块的穷举测试补充边界。
默认无总线反压；WFI与流水线冒险形成实际停顿，外部StallW/FlushM门控在独立Privileged/CSR模块测试中覆盖。

## 7. 首次失败、根因、处理与复验

首次证据保存在`first-evidence/`，构建错误/中断运行日志同样归档。首轮Scala发现预期错误后中断（退出130），不能计入完整回归。

| 问题 | 触发与首次现象 | 根因与处理 | 复验 |
| --- | --- | --- | --- |
| MPIE预期错误 | U/S异常日志status比预期少0x80 | Trap覆盖MPIE←原MIE（为0）；不能沿用MRET设置的MPIE=1。修正预期；DUT不变 | EX08/EX09、权限/非法返回/TW/计数权限系列最终两层全部通过 |
| TVEC WARL预期错误 | CS03写0x7ff实际读0x7c1 | 本平台以MODE最低位选择向量，并将BASE对齐64字节；该选择已在CSRMachineSpec独立固定字面量检查。修正夹具 | 四配置CS03通过 |
| S类优先级预期错误 | IRPRIS5期望STI、实际SSI | 官方顺序SEI > SSI > STI；修正测试组合与独立预期，保留首次RTL JSON与events | IRPRIS9/1/5和全部IRQ用例通过 |
| 关闭S的委托CSR夹具错误 | minimal-CS03没有存储并超时10000拍 | 本实现关闭S时medeleg/mideleg不存在；从正常掩码读写表剔除，另以CS08检查非法访问 | minimal-CS03/CS08通过 |
| HPM原始冒险事件夹具 | HPM4多计1次 | 原始LoadStallD也包含年轻CSR读后存储的依赖，恰与inhibit提交同拍；在停止计数与读取签名之间加入NOP隔离，预期仍由两组load-use独立推出 | HPM4最终通过 |
| Sstc初始pending预期 | STPER1预期0实际32 | stimecmp和TIME均复位0，STCE开启且比较为大于等于，故合法STIP=1；修正独立预期 | STPER1最终通过 |
| 向量slot1夹具放置错误 | 导出器org要求失败 | slot1距BASE只有4字节，双指令poison超过槽空间。仅保留一条poison store | 所有向量用例通过，访问BASE即产生额外存储并失败 |
| Scala夹具编译问题 | JSON字符串推断、嵌套块、ParallelTestExecution返回类型 | 显式ujson.Str/locally/具体newInstance类型；均限测试代码 | 最终完整compile/test通过 |

独立审阅还发现验证基础设施的观察窗口边界、新鲜性及JUnit完整性问题：均先以负向测试复现，再修复并重新运行全部证据流水线。报告器同时拒绝超限、负数和无序周期记录。

新鲜性门控曾拒绝共享旧目录中仍由先前中断JVM写入的记录，随后把完整验证的全部产物隔离到唯一运行目录；最终只归档隔离目录内的完整证据。

本轮未发现必须修复的生产核心缺陷；所有修正都限定于新增测试基础设施。原始三文件快照不混入这些修正。
最终每个RTL用例三种种子在同一实例上两次复位均通过，完整签名一致；cycles的种子差异来自等待延迟，已逐运行记录。

## 8. 检查器负向验证与报告完整性

负向测试直接运行同一生产RTL驱动，仅修改预期或周期上限：

| 故意错误 | 检查器失败原因 | 返回码 | 证据 |
| --- | --- | --- | --- |
'''
    for value in negative:
        name = value['name']
        text += f'| {name} | {value["failure"]} | 1 | [JSON](../reports/chapter8-testplan/negative/{name}.json) / [日志](../reports/chapter8-testplan/negative/{name}.log) |\n'
    text += '''
`signature`翻转首条预期数据；`missing`在期望尾部增加DUT未产生的事件；`extra`删除完成存储预期，使实际完成存储成为额外事件；`timeout`限定1拍。
首次负向验证在驱动尚未实现时失败；审阅另复现完成沿接近周期上限时漏检观察窗口，新增observation-window负向用例要求失败，修复两层驱动后复验。RED日志保留；实现后五种错误均被检测，测试框架因此通过，见[negative-green.log](../reports/chapter8-testplan/negative-green.log)。
报告入口首先新建独立runs目录（current-run.txt指向本轮），隔离镜像、签名、日志、Scala工作目录与JUnit，避免并发/中断旧任务混写；记录session.json的UTC起点和全部验证源码SHA-256，最终要求源码未变化，所有本轮结果、JUnit和日志均晚于起点；完整回归清除CHAPTER8_CASE_FILTER。报告器核对全部共享JUnit身份、29个suite清单、590个testcase与日志总数，拒绝跳过、失败或缺失记录；重新核对每次签名、复位身份、种子、完成后8拍和合法周期。
报告器自身负向单元测试检查失败、缺失/错签名、观察拍数不足，不允许由汇总把不完整记录转为通过。

## 9. 归档、校验与复现

归档入口：[results.csv](../reports/chapter8-testplan/results.csv)、[results.json](../reports/chapter8-testplan/results.json)、[metadata.json](../reports/chapter8-testplan/metadata.json)、[SHA256SUMS](../reports/chapter8-testplan/SHA256SUMS)。
所有报告引用的镜像、预期events、实际JSON、日志、源码差异与工具记录在`docs/reports/chapter8-testplan/`；SHA256SUMS覆盖归档全部文件，不包含自身。

```sh
cd docs/reports/chapter8-testplan
sha256sum -c SHA256SUMS
cd ../../..
make report-chapter8 SBT=./scripts/sbt-local.sh
```

请在仓库根目录使用本地固定版本工具链；也可指定SBT/VERILATOR。复现不需要RISC-V汇编器，指令由既有独立手编码器生成。
本地CVW和教材只用于报告的来源指纹，运行Scala和独立RTL不依赖CVW的外设、Spike或网络。
仅复验单个Scala场景可用`CHAPTER8_CASE_FILTER='default-EX02' ./scripts/sbt-local.sh 'testOnly riscvpipeline.Chapter8TestPlanSpec'`；完整报告必须使用全量证据。
'''
    text = text.replace('../reports/chapter8-testplan/', '')
    text = text.replace('docs/reports/chapter8-testplan', str(ART.relative_to(ROOT)))
    (ART / 'report.md').write_text(text)
    (ARCHIVE / 'source.diff').write_text(capture(['git', 'diff', BASE, '--', 'src', 'scripts', 'Makefile', 'build.sbt']))
    # Store all newly added source content as well: git diff omits untracked files.
    for path in paths:
        if path.is_file() and (path.name.startswith(('Chapter8', 'test-chapter8', 'report-chapter8', 'test_chapter8'))):
            target = ARCHIVE / 'source' / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
    sums = [f'{digest(p)}  {p.relative_to(ARCHIVE)}' for p in sorted(ARCHIVE.rglob('*'))
            if p.is_file() and p.name != 'SHA256SUMS']
    (ARCHIVE / 'SHA256SUMS').write_text('\n'.join(sums) + '\n')
    publish_report(ART / 'report.md', REPORT, 'make report-chapter8 SBT=./scripts/sbt-local.sh')
    print(f'Report written: {len(cases)} cases, {len(runs)} checked runs, {len(sums)} archived files')

if __name__ == '__main__':
    if sys.argv[1:] == ['--begin']:
        begin()
    elif sys.argv[1:] == ['--path']:
        print(ART.relative_to(ROOT))
    elif sys.argv[1:]:
        raise SystemExit('usage: report-chapter8.py [--begin|--path]')
    else:
        main()
