#!/usr/bin/env python3
"""Adapt pinned CVW RV32 privilege tests; no DUT output enters the oracle."""
import argparse
import difflib
import hashlib
import importlib.util
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
import subprocess
from cvw_common import ROOT, Collector, dump, fresh_run

CVW = Path(os.environ.get('CVW_ROOT', '/home/unlastingstar/cvw'))
REVISION = '2f82f001139358be7646e910ff6fa2a140bed620'
UPSTREAM = Path('tests/wally-riscv-arch-test')
REL = UPSTREAM / 'riscv-test-suite/rv32i_m/privilege'
APPLICABLE = ('minfo', 'misa', 'mtvec', 'stvec', 'mie', 'sie', 'csr-permission-u',
              'csr-permission-s', 'trap', 'trap-s', 'trap-u', 'trap-sret',
              'status-mie', 'status-sie', 'status-tw', 'status-tvm', 'endianness', 'wfi')
EXCLUDED = {'mmu-sv32': '需要 Sv32 页表与地址翻译，当前为 Bare',
            'mmu-sv32-svadu': '需要 Sv32 与 Svadu 自动 A/D 更新',
            'satp-invalid': '需要实际地址翻译及取指页故障',
            'pmp': '需要 PMP 寄存器及权限匹配单元',
            'pma': '需要 PMA 属性及设备存储映射',
            'status-fp-enabled': '需要浮点寄存器及 F/D 指令',
            'lrsc': '需要 A 扩展保留集及 LR/SC',
            'cbom': '需要 Zicbom 和缓存管理',
            'cboz': '需要 Zicboz 缓存块清零',
            'pwm': '需要 PWM/GPIO/PLIC 外设'}
FLAGS = ['-march=rv32i_zicsr_zifencei', '-mabi=ilp32', '-mno-relax', '-nostdlib',
         '-nostartfiles', '-fno-pic', '-msmall-data-limit=0', '-DXLEN=32']


def pinned(relative):
    path = CVW / relative
    original = subprocess.check_output(['git', '-C', str(CVW), 'show', f'{REVISION}:{relative}'])
    if not path.is_file() or path.read_bytes() != original:
        raise ValueError(f'Missing or changed pinned upstream source: {relative}')
    return original


def independent(name, body, syms):
    if name == 'minfo':
        return [2, 17] * 5 + [11]
    if name == 'misa':
        return [0x111, 11]
    if name.startswith('csr-permission-'):
        # Every write AND read is illegal in the lower mode. t5 retains 0xbad.
        count = len(re.findall(r'^WRITE_READ_CSR\s', body, re.M))
        return [11] + [2, 2, 0xbad] * count + [8 if name.endswith('-u') else 9]
    if name == 'trap-sret':
        return [11, 0, 0x1800, 2, 0x10200073, 0x800, 9, 0, 0x800]
    raise ValueError(f'No independently reviewed oracle for {name}')


def relocate_reference(text, syms):
    values = []
    for line in text.splitlines():
        match = re.match(r'^\s*([\da-fA-F]{8})\b', line)
        if not match:
            continue
        value = int(match[1], 16)
        if value == 0x80000168:
            value = syms['cause_breakpnt']
        elif value == 0x80000171:
            value = syms['cause_load_addr_misaligned'] + 1
        elif value == 0x80000189:
            value = syms['cause_store_addr_misaligned'] + 1
        elif value == 0xb3fe and 'medeleg' in line:
            value = 0x3ff  # Bare RV32: causes 0..9, no page-fault delegation.
        elif value == 0 and values and values[-1] in (1, 5, 7) and re.search(r'[ms]tval', line):
            value = 0x10000
        values.append(value)
    return values


def replace_macro(text, name, body):
    pattern = r'(?ms)^\.macro ' + name + r'(?:\s[^\n]*)?\n.*?^\.endm'
    result, n = re.subn(pattern, lambda m: '.macro ' + name + '\n' + body + '\n.endm', text)
    assert n == 1, (name, n)
    return result


def adapt_library(text, sstc):
    text = text.replace('#include "model_test.h"\n#include "arch_test.h"', '#include "platform.h"')
    text = re.sub(r'    // set up PMP.*?(?=\n\n.endm)',
                  '    // Bare platform: no PMP; all implemented memory is accessible.\n    li s11, 0\n' +
                  ('    li t0, -1\n    csrw stimecmp, t0\n    csrw stimecmph, t0\n    li t0, 0x80000000\n    csrw menvcfgh, t0\n' if sstc else ''), text, flags=re.S)
    text = text.replace('jalr zero // cause instruction access trap',
                        'li t3, 0x10000\n    jalr t3 // cause instruction access trap')
    text = text.replace('lw t4, 0(zero)', 'li t3, 0x10000\n    lw t4, 0(t3)')
    text = text.replace('sw t4, 0(zero)', 'li t3, 0x10000\n    sw t4, 0(t3)')
    # Marker stores replace CLINT/PLIC/GPIO transactions; pins are driven by the RTL harness.
    start = text.index('cause_m_time_interrupt:')
    end = text.index('end_trap_triggers:', start)
    trigger = '''cause_m_time_interrupt:
    li t3, 0xff20
    li t4, 7
    sw t4, 0(t3)
    li a3, 64
1:  addi a3, a3, -1
    bnez a3, 1b
    ret
cause_s_time_interrupt:
'''
    if sstc:
        trigger += '''    li t3, 1
    csrw stimecmp, t3
    csrw stimecmph, zero
    li t3, 0xff20
    li t4, 5
    sw t4, 0(t3)
'''
    else:
        trigger += '    li t3, 32\n    csrs mip, t3\n'
    trigger += '''    li a3, 64
1:  addi a3, a3, -1
    bnez a3, 1b
    ret
cause_m_soft_interrupt:
    li t3, 0xff20
    li t4, 3
    sw t4, 0(t3)
    ret
cause_s_soft_interrupt:
    csrsi sip, 2
    ret
cause_s_soft_from_m_interrupt:
    csrsi mip, 2
    ret
cause_m_ext_interrupt:
    li t3, 0xff20
    li t4, 11
    sw t4, 0(t3)
    li a3, 64
1:  addi a3, a3, -1
    bnez a3, 1b
    ret
cause_s_ext_interrupt_GPIO:
    li t3, 0xff20
    li t4, 9
    sw t4, 0(t3)
    li a3, 64
1:  addi a3, a3, -1
    bnez a3, 1b
    ret
'''
    text = text[:start] + trigger + text[end:]
    # Trap setup's timer disable is a protocol reset, not an unmapped CLINT store.
    text = text.replace('la t4, 0x02004000', 'li t4, 0xff20')
    text = text.replace('li t5, 0xFFFFFFFF\n    sw t5, 0(t4)', 'li t5, 0\n    sw t5, 0(t4)')
    for label, nxt in [('soft_interrupt_', 'time_interrupt_'),
                       ('time_interrupt_', 'ext_interrupt_'),
                       ('ext_interrupt_', '    // Table of trap behavior')]:
        start = text.index('\n' + label + '\\MODE\\():')
        end = text.index('\n' + nxt, start) if nxt.endswith('_') else text.index(nxt, start)
        timer = ''
        if label == 'time_interrupt_' and sstc:
            timer = '    li t2, -1\n    csrw stimecmp, t2\n    csrw stimecmph, t2\n'
        mask = {'soft_interrupt_': 2, 'time_interrupt_': 32, 'ext_interrupt_': 512}[label]
        replacement = '\n' + label + '\\MODE\\():\n' + timer + '''    li t0, 0xff20
    sw zero, 0(t0)
    li t0, MASK
    csrc \\MODE\\()ip, t0
    csrr ra, \\MODE\\()epc
    j trapreturn_finished_\\MODE\\()
\n'''.replace('MASK', str(mask))
        text = text[:start] + replacement + text[end:]
    # END_TESTS logs its original final ecall. Delegated S termination then uses
    # a nondelegatable machine software interrupt to enter a platform-only epilogue.
    text = replace_macro(text, 'END_TESTS', '    li s11, 1\n    j terminate_test')
    text = text.replace(r'vectored_int_end_\MODE\():', r'''vectored_int_end_\MODE\():
.if (\MODE\() == m)
    addi s11, s11, -2
    beqz s11, cvw_finish
    addi s11, s11, 2
.endif''')
    text = text.replace('    // Record trap', r'''    li t0, 2
    beq s11, t0, cvw_finish
    // Record trap''')
    text = text.replace('    // Respond to trap based on cause', r'''    beqz s11, cvw_continue_\MODE\()
.if (\MODE\() == m)
    j cvw_finish
.else
    li s11, 2
    li t0, 0xff20
    li t2, 3
    sw t2, 0(t0)
1:  j 1b
.endif
cvw_continue_\MODE\():
    // Respond to trap based on cause''')
    text = text.replace('test_1_res:\n', 'signature_guard:\n    .word 0xcafef00d\ntest_1_res:\n')
    return text


def build(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    collector = Collector(directory)
    inventory = directory / 'upstream'
    inventory.mkdir()
    hashes = {}
    files = [REL / 'src' / f'WALLY-{n}-01.S' for n in (*APPLICABLE, *EXCLUDED)]
    files += [REL / 'src/WALLY-TEST-LIB-32.h']
    files += [UPSTREAM / n for n in ('COPYING.APACHE', 'COPYING.BSD', 'COPYING.CC')]
    for name in APPLICABLE:
        relative = REL / 'references' / f'WALLY-{name}-01.reference_output'
        if (CVW / relative).exists():
            files.append(relative)
    for relative in files:
        data = pinned(relative)
        hashes[str(relative)] = hashlib.sha256(data).hexdigest()
        destination = inventory / relative.relative_to(UPSTREAM)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    dump(directory / 'upstream.json', dict(revision=REVISION, root=str(CVW), sha256=hashes))
    coverage = []
    programs = []
    tool = os.environ.get('RISCV_TOOL_PREFIX', 'riscv64-unknown-elf-')
    def command(key, args):
        result = collector.command(key, args, timeout=120)
        if result['exit']:
            raise RuntimeError(f'{key} failed: {result["log"]}')
    for name in (*APPLICABLE, *EXCLUDED):
        coverage.append(dict(name='WALLY-' + name + '-01', source=str(REL / 'src' / f'WALLY-{name}-01.S'),
                             book='§8.3 表8.5–8.9', applicable=name in APPLICABLE,
                             reason=EXCLUDED.get(name, '原主体、陷阱签名；平台启动/地址/中断/结束协议适配'),
                             checks=name if name != 'status-tvm' else 'TVM: satp 读写和 sfence.vma 访问限制；无地址翻译'))
    dump(directory / 'coverage.json', coverage)
    (directory / 'coverage.md').write_text('# 教材第八章覆盖表\n\n|教材要求|上游文件|适配内容/不适用理由|检查项|\n|---|---|---|---|\n' +
        ''.join(f'|{r["book"]}|{r["source"]}|{r["reason"]}|{r["checks"]}|\n' for r in coverage))
    original_lib = (CVW / REL / 'src/WALLY-TEST-LIB-32.h').read_text()
    for config, sstc in [('default', False), ('sstc-hpm', True)]:
        shared = directory / config
        shared.mkdir()
        lib = adapt_library(original_lib, sstc)
        (shared / 'WALLY-TEST-LIB-32.h').write_text(lib)
        shutil.copyfile(ROOT / 'programs/chapter8-cvw/platform.h', shared / 'platform.h')
        (shared / 'library.patch').write_text(''.join(difflib.unified_diff(original_lib.splitlines(True),
                      lib.splitlines(True), fromfile='upstream/WALLY-TEST-LIB-32.h', tofile='adapted/WALLY-TEST-LIB-32.h')))
        for name in APPLICABLE:
            row = dict(name=f'WALLY-{name}-01', config=config, status='not_executed', reason='')
            programs.append(row)
            if name == 'trap' and not sstc:
                row.update(status='not_applicable', reason='原样例显式要求 Sstc；默认配置关闭 Sstc')
                continue
            work = shared / row['name']
            work.mkdir()
            row['directory'] = str(work)
            try:
                original = (CVW / REL / 'src' / f'{row["name"]}.S').read_text()
                body = original.replace('0x8000F000', '0xf800')
                if name == 'wfi':
                    a, b = body.index('    li x28, 0x100'), body.index('auipc ra, 0x0')
                    body = body[:a] + '    li x29, 0xff20\n    li x28, 0x107\n    sw x28, 0(x29)\n\n' + body[b:]
                source = work / f'{row["name"]}.S'
                source.write_text(body)
                (work / 'source.patch').write_text(''.join(difflib.unified_diff(original.splitlines(True), body.splitlines(True), fromfile='upstream/' + source.name, tofile='adapted/' + source.name)))
                init = work / 'init.S'
                init.write_text('.section .initdata,"ax"\n.global init_data\ninit_data: ret\n')
                elf = work / 'program.elf'
                def link():
                    command(f'{config}/{row["name"]}/link', [tool+'gcc', *FLAGS, '-I'+str(shared), source, init,
                             '-Wl,--no-relax', '-T', ROOT/'programs/chapter8-cvw/link.ld', '-o', elf])
                link()
                syms_text = subprocess.check_output([tool+'nm', str(elf)], text=True)
                syms = {f[2]: int(f[0], 16) for l in syms_text.splitlines() if len(f := l.split()) == 3}
                command(f'{config}/{row["name"]}/data', [tool+'objcopy', '-O', 'binary', '--only-section=.data', elf, work/'data.bin'])
                raw = (work / 'data.bin').read_bytes()
                lines = ['.section .initdata,"ax"', '.global init_data', 'init_data:', 'li t0, 0xc000']
                for offset in range(0, len(raw), 4):
                    if offset and offset % 2048 == 0:
                        lines += ['addi t0,t0,2047', 'addi t0,t0,1']
                    value = int.from_bytes(raw[offset:offset+4], 'little')
                    lines += [f'li t2,0x{value:08x}', f'sw t2,{offset%2048}(t0)']
                lines += ['ret']
                init.write_text('\n'.join(lines)+'\n')
                link()
                command(f'{config}/{row["name"]}/data-final', [tool+'objcopy', '-O', 'binary', '--only-section=.data', elf, work/'data-final.bin'])
                assert (work/'data-final.bin').read_bytes() == raw, 'initializer changed linked addresses'
                disassembly = subprocess.check_output([tool+'objdump', '-d', '-Mno-aliases', str(elf)], text=True)
                (work/'program.disassembly').write_text(disassembly)
                (work/'symbols.txt').write_text(syms_text)
                command(f'{config}/{row["name"]}/image', [tool+'objcopy', '-O', 'binary', '--only-section=.text', elf, work/'code.bin'])
                raw_code = (work/'code.bin').read_bytes()
                assert len(raw_code) <= 65536 and len(raw_code) % 4 == 0
                words = [int.from_bytes(raw_code[i:i+4], 'little') for i in range(0, len(raw_code), 4)]
                (work/'program.hex').write_text(''.join(f'{w:08x}\n' for w in words + [0x13]*(16384-len(words))))
                reference = CVW / REL / 'references' / f'{row["name"]}.reference_output'
                expected = relocate_reference(reference.read_text(), syms) if reference.exists() else independent(name, original, syms)
                assert 0 < len(expected) < 1024
                (work/'reference.signature').write_text(''.join(f'{v:08x}\n' for v in expected + [0xdeadbeef]*(1024-len(expected))))
                dump(work/'oracle.json', dict(origin=str(reference) if reference.exists() else 'independent instruction semantics',
                     usedWords=len(expected), usedBytes=len(expected)*4, words=expected,
                     adjustments=['fault address 0 → 0x10000', 'breakpoint/misaligned mtval → ELF symbols',
                                  'medeleg 0xb3fe → Bare 0x3ff', 'interrupt peripherals → pin markers',
                                  'default supervisor timer → software STIP; Sstc → stimecmp/TIME_REGW']))
                row.update(status='built', signatureWords=len(expected), sigbase=syms['test_1_res'])
            except Exception as exc:
                row.update(status='build_platform_error', reason=str(exc))
    dump(directory/'manifest.json', programs)
    return programs


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    programs = build(args.output or fresh_run() / 'chapter8-cvw')
    sys.exit(int(any(p['status'] == 'build_platform_error' for p in programs)))
