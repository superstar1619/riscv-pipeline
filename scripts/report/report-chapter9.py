#!/usr/bin/env python3
"""Recheck bound, archived Chapter 9 evidence before publishing acceptance."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix=str(Path(__file__).resolve().parents[2]/'target/cache/python')
os.environ['PYTHONPYCACHEPREFIX']=sys.pycache_prefix
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'lib'))
from cvw_common import dump
from report_catalog import register_report

CASES=('memory-switch','apb-subword','privilege-endian','interrupt-precise')
CONFIGS={f'{mode}-ram{lat}' for mode in ('bus-only','mixed') for lat in (0,1,2)}

def read(path): return json.loads(Path(path).read_text())
def load(path,name):
    spec=importlib.util.spec_from_file_location(name,path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def source_check(directory):
    before,after=read(directory/'source-before.json'),read(directory/'source-after.json')
    if before!=after or not read(directory/'source-stability.json').get('passed'): raise ValueError('source before/after differs')
    required={'scripts/lib/cvw_common.py','scripts/lib/commit_diff.py','scripts/lib/report_catalog.py',
              'scripts/run/test-chapter9-rtl.py','scripts/report/report-chapter9.py',
              'src/test/scala/riscvpipeline/Chapter9Observer.scala'}
    if not required<=set(before): raise ValueError('required source/helper snapshot absent')
    for relative,digest in before.items():
        path=(directory/'source'/relative).resolve()
        if not path.is_relative_to(directory/'source') or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
            raise ValueError('archived source hash mismatch: '+relative)
    return load(directory/'source/scripts/run/test-chapter9-rtl.py','chapter9_archived_checker')

def command_check(directory):
    records=[json.loads(l) for l in (directory/'commands.jsonl').read_text().splitlines() if l.strip()]
    platform=[r for r in records if r['key']=='python-platform']
    if len(platform)!=1 or platform[0]['exit']!=0: raise ValueError('Python platform command missing/failed')
    return records

def spike_check(directory,checker):
    work=directory/'programs/bus-only/memory-switch'
    reference=read(work/'spike/reference.json')
    if reference!=read(directory/'spike-references.json')['bus-only/memory-switch']:
        raise ValueError('Spike reference copies differ')
    if Path(reference['log']).resolve()!=directory/'spike/reference.log': raise ValueError('foreign Spike log')
    original=(work/'program.bin').read_bytes(); relocated=(work/'spike/program.bin').read_bytes()
    if original!=relocated or hashlib.sha256(original).hexdigest()!=reference['instructionSHA256']:
        raise ValueError('Spike relocation instruction bytes differ')
    parser=load(directory/'source/scripts/lib/commit_diff.py','chapter9_archived_spike_parser')
    lines=[l for l in Path(reference['log']).read_text().splitlines() if not l.startswith('warning: tohost and fromhost symbols not in ELF')]
    commits=[];done=False
    for item in parser.spike_records(lines,0x80002000):
        pc,value=item['pc'],item['value']
        if 0x80002000<=pc<0x80003000: pc=pc-0x80002000+0x1000
        if item['instruction']&0x7f in (0x67,0x6f) and item['rd']:
            if value!=(item['pc']+4)&0xffffffff: raise ValueError('raw Spike link differs from PC+4')
            if 0x80002000<=item['pc']<0x80003000: value=value-0x80002000+0x1000
        commits.append(dict(pc=pc,instruction=item['instruction'],rd=item['rd'],data=value))
        mem=item['mem']
        if mem and mem['kind']=='store' and mem['address']==0x8000ff00 and mem.get('data')==0x5a:
            done=True;break
    if not done or commits!=reference['commits']: raise ValueError('cached Spike reference differs from raw log')
    return reference

def coverage_check(directory):
    coverage=read(directory/'code-coverage.json')
    if len(coverage)!=6 or {c['config'] for c in coverage}!=CONFIGS: raise ValueError('coverage configuration inventory')
    for item in coverage:
        config=item['config'];work=directory/'coverage'/config
        expected={directory/'rtl'/config/case/f'coverage-s{seed}.dat' for case in CASES for seed in (1,17,2026)}
        files=[Path(p).resolve() for p in item['files']]
        if item['status']!='pass' or len(files)!=12 or set(files)!=expected: raise ValueError('missing/foreign/duplicate coverage files')
        for path in [*files,work/'coverage.dat',work/'coverage.info']:
            if not path.is_file() or not path.stat().st_size: raise ValueError('empty/missing coverage artifact: '+str(path))
        counts=[int(l.split(',')[1]) for l in (work/'coverage.info').read_text().splitlines() if l.startswith('DA:')]
        toggles=[int(l.rsplit(' ',1)[1]) for l in (work/'coverage.dat').read_text().splitlines() if 'v_toggle' in l]
        actual=dict(linePoints=len(counts),hitLinePoints=sum(c>0 for c in counts),togglePoints=len(toggles),hitTogglePoints=sum(c>0 for c in toggles))
        if not counts or not toggles or any(item[k]!=v for k,v in actual.items()): raise ValueError('coverage counts differ from raw artifacts')
    return coverage

def report(directory):
    directory=Path(directory).resolve(); plan=read(directory/'manifest.json');rows=read(directory/'results.json')
    def key(row): return tuple(row[k] for k in ('name','config','seed','repeat'))
    full_keys={(case,config,seed,repeat) for case in CASES for config in CONFIGS for seed in (1,17,2026) for repeat in (0,1)}
    missing=[r for r in plan['requiredRuns'] if sum(key(x)==key(r) for x in rows)!=1]
    full_matrix={key(r) for r in rows}==full_keys and len(rows)==144
    errors=[];source_stable=False;commands=[];checker=None;reference=None;code_coverage_passed=False
    try: checker=source_check(directory);source_stable=True
    except Exception as exc: errors.append(dict(stage='source',reason=str(exc)))
    try: commands=command_check(directory)
    except Exception as exc: errors.append(dict(stage='commands',reason=str(exc)))
    if checker:
        try: reference=spike_check(directory,checker)
        except Exception as exc: errors.append(dict(stage='spike',reason=str(exc)))
    evidence_paths=set();trace_cache={};checked_rows={}
    for row in rows:
        if row['status']!='pass': continue
        try:
            case,config,seed,repeat=key(row)
            if (case,config,seed,repeat) not in full_keys: raise ValueError('unknown matrix row')
            mode=config.rsplit('-ram',1)[0];work=directory/'rtl'/config/case
            evidence=work/f'actual-s{seed}-r{repeat}.json';trace=work/f'trace-s{seed}.jsonl'
            if Path(row['evidence']).resolve()!=evidence or Path(row['trace']).resolve()!=trace: raise ValueError('foreign or stale row path')
            if evidence in evidence_paths: raise ValueError('duplicate row evidence path')
            evidence_paths.add(evidence)
            if not checker: raise ValueError('archived checker/source validation failed')
            if trace not in trace_cache:
                events,error=checker.read_trace(trace)
                if error: raise ValueError('raw trace parse: '+error)
                trace_cache[trace]=events
            events=[e for e in trace_cache[trace] if e['repeat']==repeat]
            actual=read(evidence)
            if actual['events']!=events: raise ValueError('actual events differ from raw trace/reset identity')
            oracle=checker.configured_oracle(read(directory/'programs'/mode/case/'oracle.json'),int(config.rsplit('-ram',1)[1]));oracle['requirePhysical']=True
            checked=checker.check_trace(oracle,events)
            if not checked['pass']: raise ValueError('archived trace recheck: '+checked['failure'])
            if row.get('exit')!=0: raise ValueError('passing row has nonzero process exit')
            matching=[r for r in commands if r['key']==f'rtl/{config}/{case}/seed{seed}']
            if len(matching)!=1 or matching[0]['exit']!=0 or str(trace) not in matching[0]['argv']:
                raise ValueError('raw simulator command missing/failed/mismatched')
            if case=='memory-switch' and mode=='bus-only':
                diff=checker.compare_spike(reference or {},events)
                if not diff['passed']: raise ValueError('Spike recheck: '+diff['reason'])
            checked_rows[key(row)]=(oracle,events)
        except Exception as exc: errors.append(dict(key=key(row),reason=str(exc)))
    negative_passed=False
    try:
        if not checker: raise ValueError('archived checker unavailable')
        oracle,baseline=checked_rows[('memory-switch','bus-only-ram0',1,0)]
        names={'middle-store','missing-store','duplicate-store','actual-lw','missing-lw','short-window','late-store'}
        negative=read(directory/'negative-checks/results.json')
        if {r['name'] for r in negative['checks']}!=names: raise ValueError('negative inventory differs')
        for name in names:
            events=read(directory/'negative-checks'/(name+'.json'))['events']
            if events==baseline or checker.check_trace(oracle,events)['pass']: raise ValueError('negative trace incorrectly accepted: '+name)
        negative_passed=True
    except Exception as exc: errors.append(dict(stage='negative',reason=str(exc)))
    negative_spike_passed=False
    try:
        oracle,baseline=checked_rows[('memory-switch','bus-only-ram0',1,0)]
        events=read(directory/'negative-spike.json')['events']
        if events==baseline or not checker.check_trace(oracle,events)['pass'] or checker.compare_spike(reference,events)['passed']:
            raise ValueError('transient commit mutation does not distinguish signature from Spike')
        negative_spike_passed=True
    except Exception as exc: errors.append(dict(stage='negative-spike',reason=str(exc)))
    negative_irq_passed=False
    try:
        oracle,baseline=checked_rows[('interrupt-precise','bus-only-ram0',1,0)]
        names={'trap-before-rise','rise-after-trap','missing-rise','missing-fall','premature-fall','fall-before-edge','trap-pin-low','trap-committed'}
        negative=read(directory/'negative-irq/results.json')
        if {r['name'] for r in negative['checks']}!=names: raise ValueError('IRQ negative inventory differs')
        for name in names:
            events=read(directory/'negative-irq'/(name+'.json'))['events']
            if events==baseline or checker.check_trace(oracle,events)['pass']: raise ValueError('IRQ negative trace accepted: '+name)
        negative_irq_passed=True
    except Exception as exc: errors.append(dict(stage='negative-irq',reason=str(exc)))
    try: coverage_check(directory);code_coverage_passed=True
    except Exception as exc: errors.append(dict(stage='coverage',reason=str(exc)))
    selected_passed=bool(rows) and not missing and len(rows)==len(plan['requiredRuns']) and all(r['status']=='pass' for r in rows) and not errors
    passed=selected_passed and full_matrix and source_stable and negative_passed and negative_irq_passed and negative_spike_passed and code_coverage_passed
    dump(directory/'summary.json',dict(passed=passed,selectedPassed=selected_passed,fullMatrix=full_matrix,
        negativePassed=negative_passed,negativeIrqPassed=negative_irq_passed,negativeSpikePassed=negative_spike_passed,sourceStable=source_stable,codeCoveragePassed=code_coverage_passed,
        evidenceErrors=errors,requiredRuns=len(plan['requiredRuns']),recordedRuns=len(rows),missingOrDuplicate=len(missing),
        statuses={s:sum(r['status']==s for r in rows) for s in sorted({r['status'] for r in rows})}))
    text=f'# 第九章整机验证\n\n运行：`{directory.name}`；结论：**{"PASS" if passed else "未完成 / FAIL"}**。\n\n'
    text+=f'必要记录 144；本轮清单 {len(plan["requiredRuns"])}；实际 {len(rows)}；缺失或重复 {len(missing)}。两种模式 × RAM 延迟 0/1/2 × 种子 1/17/2026 × 同实例两次复位。\n\n'
    text+='验收重新解析本轮原始 trace，绑定每个配置/用例/seed/reset 的唯一路径，使用归档预期与检查器重新验证真实 LW、完整 store/物理 byte lane、因果 IRQ、八拍窗口与 Spike 提交。源码/依赖快照哈希、平台命令退出码、负向 trace 和实际 line/toggle 文件同时参与判定。\n\n'
    text+='|用例|配置|种子|复位|状态|原因|\n|---|---|---|---|---|---|\n'+''.join(f'|{r["name"]}|{r["config"]}|{r["seed"]}|{r["repeat"]}|{r["status"]}|{r.get("reason", "").replace("|", "/")}|\n' for r in rows)
    text+='\n证据复核问题见 summary.json/evidenceErrors；失败、未执行与旧检查点均保留。原 CVW `ahb64_ram_{0/1/2}_{0/1}_rv64gc` 六组合、fadd_b11-01、RV64GC、浮点、缓存和 burst 均未覆盖。纯总线 memory-switch 使用指令字节相同的 .text 地址适配 Spike 差分，只映射 ROM PC 和经验证的 JAL/JALR PC+4 link，无跳过/重同步；它不等同于相同装载地址差分。混合本地 DTIM 与 Spike debug 区冲突，完整 Spike 差分未执行；MMIO/特权使用独立预期。\n'
    (directory/'report.md').write_text(text)
    register_report(directory,'chapter9','python3 scripts/run/test-chapter9-rtl.py',status='pass' if passed else 'fail')
    return passed
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('directory',type=Path)
    args=parser.parse_args();sys.exit(0 if report(args.directory) else 1)
