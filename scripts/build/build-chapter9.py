#!/usr/bin/env python3
"""Build CPU-initialized Chapter 9 RV32 images and specification-derived oracles."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
if not sys.pycache_prefix:
    sys.pycache_prefix = str(Path(__file__).resolve().parents[2] / 'target/cache/python')
os.environ['PYTHONPYCACHEPREFIX'] = sys.pycache_prefix
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'lib'))
from cvw_common import ROOT, Collector, dump

CASES = ('memory-switch', 'apb-subword', 'privilege-endian', 'interrupt-precise')
FLAGS = ['-march=rv32i_zicsr_zifencei', '-mabi=ilp32', '-mno-relax', '-nostdlib',
         '-nostartfiles', '-fno-pic', '-msmall-data-limit=0', '-Wl,--no-relax']

def oracle_words(case, mixed, symbols=None):
    # Hand-derived architectural results, never read from DUT output.
    if case == 'memory-switch':
        return [0x807f00ff, 0xffffff80, 0x80, 0xffff807f, 0x807f, 0,
                0xffffff80, 0x123480ff, 42, 0x13579bdf] + ([0x55aa33cc] if mixed else [])
    if case == 'apb-subword':
        return [0x11223344, 0x1122aa44, 0xbbccaa44, 3]
    if case == 'privilege-endian':
        return [0x44332211, 11, 0, 0x99]
    if case == 'interrupt-precise':
        return [0x80000003, (symbols or {}).get('irq_resume', 0), 0, 0x99]
    raise ValueError(f'unknown case {case}')

def expected_stores(case, mixed, symbols=None):
    sig = iter(0x80008000 + 4*i for i in range(32))
    result = []
    def store(a, d, m=15): result.append([a, d, m])
    def emit(d): store(next(sig), d)
    words = oracle_words(case, mixed, symbols)
    if case == 'memory-switch':
        store(0x80004000, 0x807f00ff)
        for value in words[:6]: emit(value)
        store(0x80004001, 0x80808080, 2); emit(words[6])
        store(0x80004002, 0x12341234, 12); emit(words[7])
        store(0x80000100, 0x02a00593); store(0x80000104, 0x00008067)
        for value in words[8:10]: emit(value)
        if mixed: store(0x800, 0x55aa33cc); emit(words[10])
    elif case == 'apb-subword':
        for (a,d,m), value in zip([(0x10000000,0x11223344,15),
                                  (0x10000001,0xaaaaaaaa,2),
                                  (0x10000002,0xbbccbbcc,12)], words):
            store(a,d,m); emit(value)
        emit(words[3])
    elif case == 'privilege-endian':
        store(0x80004000, 0x11223344)
        for value in words: emit(value)
    elif case == 'interrupt-precise':
        store(0x80004000, 0x2468ace0); store(0x8000fe00, 1)
        for value in words: emit(value)
    return result

def hex_image(raw):
    if len(raw) % 4: raise ValueError('instruction image must be word aligned')
    if len(raw) > 4096: raise ValueError('instruction image exceeds 4 KiB')
    return ''.join(f'{int.from_bytes(raw[i:i+4], "little"):08x}\n' for i in range(0,len(raw),4)) + '00000013\n'*((4096-len(raw))//4)

def build(directory, cases=CASES):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    collector = Collector(directory)
    tool = os.environ.get('RISCV_TOOL_PREFIX', 'riscv64-unknown-elf-')
    collector.command('metadata/gcc', [tool+'gcc', '--version'])
    rows = []
    for mode in ('bus-only', 'mixed'):
        mixed, reset = mode == 'mixed', 0 if mode == 'mixed' else 0x1000
        for case in cases:
            work = directory / 'programs' / mode / case
            work.mkdir(parents=True, exist_ok=False)
            for source in ('platform.h', case+'.S'):
                shutil.copyfile(ROOT/'programs/chapter9'/source, work/source)
            link = work/'link.ld'
            link.write_text('OUTPUT_ARCH(riscv)\nENTRY(_start)\nSECTIONS {\n' +
                            f' . = 0x{reset:x}; .text : {{ *(.text.init) *(.text*) }}\n' +
                            ' /DISCARD/ : { *(.comment) *(.riscv.attributes) }\n}\n' +
                            'ASSERT(SIZEOF(.text) <= 4096, "instruction ROM overflow")\n')
            row = dict(id=case, mode=mode, resetVector=reset, directory=str(work), status='not_executed')
            rows.append(row)
            def command(key, argv):
                record = collector.command(f'programs/{mode}/{case}/{key}', argv, timeout=120)
                if record['exit']: raise RuntimeError(f'{key}: {record["log"]}')
            try:
                command('link', [tool+'gcc', *FLAGS, '-DMIXED='+str(int(mixed)), '-I'+str(work),
                                 '-T', link, work/(case+'.S'), '-o', work/'program.elf'])
                command('binary', [tool+'objcopy', '-O','binary','--only-section=.text',work/'program.elf',work/'program.bin'])
                (work/'program.hex').write_text(hex_image((work/'program.bin').read_bytes()))
                # Tool inspection is metadata only; fixed expected values remain above.
                for name, args in [('disassembly',[tool+'objdump','-d','-Mno-aliases',work/'program.elf']),
                                   ('symbols',[tool+'nm',work/'program.elf'])]:
                    command(name, args)
                    (work/('program.'+name)).write_bytes((directory/f'programs/{mode}/{case}/{name}.log').read_bytes())
                syms = {f[2]:int(f[0],16) for line in (work/'program.symbols').read_text().splitlines()
                        if len(f:=line.split()) == 3}
                coverage = ['fetch.ram','fetch.return-rom','memory.subword','bus.wait','bus.lsu-ifu'] if case == 'memory-switch' else ['apb.masked','apb.count'] if case == 'apb-subword' else ['endian.big','trap.ecall'] if case == 'privilege-endian' else ['interrupt.bus-committed','interrupt.machine-software']
                if case == 'memory-switch' and mixed: coverage += ['memory.local-dtim']
                oracle = dict(origin='explicit hand-derived RV32 instruction and device semantics',
                              words=oracle_words(case,mixed,syms), stores=expected_stores(case,mixed,syms),
                              readbackPC=syms['readback_load'], requiredCoverage=coverage,
                              symbols=syms, completionObservationCycles=8, expectedTraps=([dict(cause=11,interrupt=False)] if case=='privilege-endian' else [dict(cause=3,interrupt=True,pc=syms['irq_resume'])] if case=='interrupt-precise' else []))
                dump(work/'oracle.json', oracle)
                (work/'reference.signature').write_text(''.join(f'{w:08x}\n' for w in oracle['words']))
                row.update(status='built', elf=str(work/'program.elf'), image=str(work/'program.hex'),
                           oracle=str(work/'oracle.json'), cycleLimit=30000, bytes=len((work/'program.bin').read_bytes()),
                           referenceMode='independent-events', spikeDifferential='not_executed')
            except Exception as exc: row.update(status='build_platform_error', reason=str(exc))
    dump(directory/'programs.json', rows)
    dump(directory/'source-hashes.json', {str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()
         for p in sorted((ROOT/'programs/chapter9').glob('*')) if p.is_file()})
    return rows

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--cases', nargs='+',choices=CASES,default=list(CASES))
    args = parser.parse_args()
    output = args.output or ROOT/'target/chapter9-testplan/runs'/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    rows = build(output,args.cases)
    print(output)
    return int(any(row['status'] != 'built' for row in rows))
if __name__ == '__main__': sys.exit(main())
