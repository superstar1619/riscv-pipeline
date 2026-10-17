#!/usr/bin/env python3
"""Publish only explicit Chapter 9 evidence; never promote missing runs to pass."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / 'target/cache/python')
os.environ['PYTHONPYCACHEPREFIX'] = sys.pycache_prefix
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'lib'))
from cvw_common import dump
from report_catalog import register_report

def report(directory):
    directory=Path(directory).resolve()
    plan=json.loads((directory/'manifest.json').read_text())
    rows=json.loads((directory/'results.json').read_text())
    keys=('name','config','seed','repeat')
    def key(row): return tuple(row[k] for k in keys)
    missing=[r for r in plan['requiredRuns'] if sum(key(x)==key(r) for x in rows)!=1]
    evidence_errors=[]
    for row in rows:
        if row['status']=='pass':
            try:
                value=json.loads(Path(row['evidence']).read_text())
                if value['check']['pass'] is not True or row.get('exit')!=0:
                    raise ValueError('passing status contradicts check or process exit')
            except (OSError,KeyError,ValueError,TypeError) as exc:
                evidence_errors.append(dict(key=key(row),reason=str(exc)))
    selected_passed=not evidence_errors and bool(rows) and not missing and len(rows)==len(plan['requiredRuns']) and all(r['status']=='pass' for r in rows)
    # Independent fixed acceptance inventory: editing the exported manifest cannot shrink it.
    full_keys={(case,f'{mode}-ram{lat}',seed,repeat)
               for case in ('memory-switch','apb-subword','privilege-endian','interrupt-precise')
               for mode in ('bus-only','mixed') for lat in (0,1,2) for seed in (1,17,2026) for repeat in (0,1)}
    full_matrix={key(r) for r in rows}==full_keys and len(rows)==len(full_keys)
    negative=directory/'negative-checks/results.json'
    negative_passed=negative.exists() and json.loads(negative.read_text()).get('passed') is True
    passed=selected_passed and full_matrix and negative_passed
    dump(directory/'summary.json',dict(passed=passed,selectedPassed=selected_passed,fullMatrix=full_matrix,negativePassed=negative_passed,evidenceErrors=evidence_errors,requiredRuns=len(plan['requiredRuns']),
        recordedRuns=len(rows),missingOrDuplicate=len(missing),
        statuses={s:sum(r['status']==s for r in rows) for s in sorted({r['status'] for r in rows})}))
    text=f'# 第九章整机验证\n\n运行：`{directory.name}`；结论：**{"PASS" if passed else "未完成 / FAIL"}**。\n\n'
    text+=f'必需记录 {len(plan["requiredRuns"])}，实际 {len(rows)}，缺失或重复 {len(missing)}。两种模式 × RAM 延迟 0/1/2 × 种子 1/17/2026 × 同实例两次复位。\n\n'
    text+='每例要求独立预期、有序架构 store、物理 AHB 写一致、真实提交 LW 签名读回、索引/长度/完成协议及完成后八拍。程序、ELF、反汇编、预期、实际、命令和工具版本保存在本目录。\n\n'
    text+='|用例|配置|种子|复位|状态|原因|\n|---|---|---|---|---|---|\n'
    text+=''.join(f'|{r["name"]}|{r["config"]}|{r["seed"]}|{r["repeat"]}|{r["status"]}|{r.get("reason", "").replace("|", "/")}|\n' for r in rows)
    text+='\n原 CVW `ahb64_ram_{0/1/2}_{0/1}_rv64gc` 的六组合、`fadd_b11-01`、RV64GC、浮点、缓存和突发均未覆盖。完整特权 Spike 差分未覆盖；当前独立预期不能冒充 Spike。纯总线 memory-switch 使用指令字节完全相同的 .text 地址适配 Spike 差分；仅映射 ROM PC 与经验证的 JAL/JALR PC+4 link，严格从入口至完成、无重同步。该证据不等同于相同装载地址差分。混合本地 DTIM 与 Spike debug 区域冲突，完整 Spike 差分保持未执行。功能覆盖只使用实际动态事件；line/toggle 另报原始采集结果，缺数据不声称覆盖率。\n'
    (directory/'report.md').write_text(text)
    register_report(directory,'chapter9','python3 scripts/run/test-chapter9-rtl.py',status='pass' if passed else 'fail')
    return passed
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('directory',type=Path)
    args=parser.parse_args(); sys.exit(0 if report(args.directory) else 1)
