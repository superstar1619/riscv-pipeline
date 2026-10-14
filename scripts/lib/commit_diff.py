#!/usr/bin/env python3
"""Strict, bounded-memory RV32I commit comparison (one reset epoch per RTL file).

Spike must use --log-commits and the same ELF as RTL. Only its initial reset-ROM
prefix is discarded. No resynchronization is performed after the ELF entry.
RTL JSONL records use epoch/order/cycle/pc/instruction/rd/value/mem; numbers may
be JSON integers or 0x-prefixed strings. mem is null or kind/address/width/data.
Load data is checked through register writeback, not a shadow memory.
"""
from __future__ import annotations

import argparse
from collections import deque
from contextlib import ExitStack
import json
from pathlib import Path
import re

MASK = 0xffffffff
COMMIT = re.compile(r"^core\s+(\d+):\s+([0-3])\s+(0x[0-9a-fA-F]+)\s+\((0x[0-9a-fA-F]+)\)(.*)$")
WRITE = re.compile(r"\s*x\s*(\d+)\s+(0x[0-9a-fA-F]+)")
MEMORY = re.compile(r"\s*mem\s+(0x[0-9a-fA-F]+)(?:\s+(0x[0-9a-fA-F]+))?")
WRITES_RD = {0x03, 0x13, 0x17, 0x33, 0x37, 0x67, 0x6f}
RV32I = WRITES_RD | {0x23, 0x63, 0x0f}


class TraceError(ValueError):
    def __init__(self, reason, detail):
        super().__init__(detail)
        self.reason = reason


def number(value):
    if isinstance(value, bool):
        raise ValueError('boolean is not an integer')
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise ValueError('expected integer or 0x-prefixed string')


def u32(value):
    value = number(value)
    if not 0 <= value <= MASK:
        raise ValueError(f'value outside RV32 range: {value}')
    return value


def decode(instruction):
    opcode = instruction & 0x7f
    if opcode not in RV32I:
        raise TraceError('unexpected-instruction', f'outside supported RV32I normal commits: {instruction:#010x}')
    rd = (instruction >> 7) & 31 if opcode in WRITES_RD else 0
    memory = None
    if opcode in (0x03, 0x23):
        funct3 = (instruction >> 12) & 7
        widths = {0: 1, 1: 2, 2: 4, 4: 1, 5: 2} if opcode == 3 else {0: 1, 1: 2, 2: 4}
        if funct3 not in widths:
            raise TraceError('invalid-memory-instruction', f'invalid funct3 in {instruction:#010x}')
        memory = ('load' if opcode == 3 else 'store', widths[funct3])
    return rd, memory


def spike_records(lines, entry=0x80000000):
    started = False
    order = 0
    for line_number, line in enumerate(lines, 1):
        line = line.strip()
        if not line:
            continue
        match = COMMIT.fullmatch(line)
        if not match:
            reason = 'unexpected-trap' if 'trap' in line.lower() or 'exception' in line.lower() else 'spike-parse'
            raise TraceError(reason, f'Spike line {line_number}: {line[:240]}')
        hart, privilege, pc, instruction, tail = match.groups()
        pc, instruction = u32(pc), u32(instruction)
        if hart != '0':
            raise TraceError('unexpected-hart', f'Spike line {line_number}: hart {hart}')
        if not started:
            if pc != entry:
                if 0x1000 <= pc < 0x2000:
                    continue
                raise TraceError('entry-missing', f'non-ROM PC {pc:#x} before entry {entry:#x}')
            started = True
        if privilege != '3':
            raise TraceError('unexpected-privilege', f'Spike line {line_number}: mode {privilege}')
        expected_rd, expected_mem = decode(instruction)
        rd, value, mem = 0, 0, None
        register = WRITE.match(tail)
        if register:
            rd, value = int(register[1]), u32(register[2])
            tail = tail[register.end():]
        access = MEMORY.match(tail)
        if access:
            if expected_mem is None:
                raise TraceError('unexpected-memory', f'Spike line {line_number}')
            kind, width = expected_mem
            if (access[2] is not None) != (kind == 'store'):
                raise TraceError('spike-memory-shape', f'Spike line {line_number}: {kind}')
            mem = dict(kind=kind, address=u32(access[1]), width=width)
            if kind == 'store':
                mem['data'] = u32(access[2]) & ((1 << (width * 8)) - 1)
            tail = tail[access.end():]
        if tail.strip():
            raise TraceError('spike-parse', f'unparsed Spike line {line_number}: {tail}')
        if rd != expected_rd:
            raise TraceError('spike-register-shape', f'Spike line {line_number}: expected x{expected_rd}, got x{rd}')
        if (mem is None) != (expected_mem is None):
            raise TraceError('spike-memory-missing', f'Spike line {line_number}')
        if rd == 0 and value != 0:
            raise TraceError('x0-invariant', f'Spike line {line_number}: x0={value:#x}')
        yield dict(order=order, pc=pc, instruction=instruction, rd=rd, value=value if rd else 0,
                   mem=mem, line=line_number)
        order += 1
    if not started:
        raise TraceError('entry-missing', f'ELF entry {entry:#x} absent from Spike log')


def rtl_records(lines):
    epoch = None
    last_cycle = -1
    order = 0
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            item = json.loads(line)
            pc, instruction = u32(item['pc']), u32(item['instruction'])
            rd, value = number(item['rd']), u32(item['value'])
            actual_order, cycle, actual_epoch = (number(item[k]) for k in ('order', 'cycle', 'epoch'))
            if epoch is None:
                epoch = actual_epoch
            if actual_epoch < 0 or actual_epoch != epoch:
                raise TraceError('rtl-epoch', f'RTL line {line_number}: one epoch per file required')
            if actual_order != order:
                raise TraceError('rtl-order', f'RTL line {line_number}: expected order {order}, got {actual_order}')
            if cycle <= last_cycle:
                raise TraceError('rtl-cycle', f'RTL line {line_number}: cycle must increase strictly')
            if number(item.get('x0', 0)) != 0:
                raise TraceError('x0-invariant', f'RTL line {line_number}: architectural x0 is nonzero')
            if item.get('trap', False):
                raise TraceError('unexpected-trap', f'RTL line {line_number}')
            expected_rd, expected_mem = decode(instruction)
            if rd != expected_rd:
                raise TraceError('rtl-register-shape', f'RTL line {line_number}: expected x{expected_rd}, got x{rd}')
            mem = item['mem']
            if (mem is None) != (expected_mem is None):
                raise TraceError('rtl-memory-missing', f'RTL line {line_number}')
            if mem is not None:
                kind, width = expected_mem
                if mem['kind'] != kind or number(mem['width']) != width:
                    raise TraceError('rtl-memory-width', f'RTL line {line_number}: decoded {kind}/{width}')
                normalized = dict(kind=kind, width=width, address=u32(mem['address']))
                if kind == 'store':
                    normalized['data'] = u32(mem['data']) & ((1 << (width * 8)) - 1)
                mem = normalized
            yield dict(epoch=epoch, order=order, cycle=cycle, pc=pc, instruction=instruction,
                       rd=rd, value=value if rd else 0, mem=mem, line=line_number)
            order += 1
            last_cycle = cycle
        except (KeyError, TypeError, ValueError) as error:
            if isinstance(error, TraceError):
                raise
            raise TraceError('rtl-parse', f'RTL line {line_number}: {error}') from error


def read_symbols(path):
    """Read `riscv*-nm -n ELF` output; no subprocesses or ELF dependencies."""
    symbols = {}
    for line in Path(path).read_text().splitlines():
        fields = line.split()
        if len(fields) >= 3:
            try:
                symbols[int(fields[0], 16)] = fields[2]
            except ValueError:
                pass
    return symbols


def compare_files(spike_path, rtl_path, output_path=None, *, entry=0x80000000,
                  done_address=0x8021ff00, done_value=1, elf_symbols=None):
    """Return/write a JSON-serializable report; comparison failures never resync.

    elf_symbols is an address->name mapping or a path to sorted nm text. The
    caller must stop its RTL log after the completion store (and separately
    check the eight-cycle drain window). Spike's host-exit suffix is ignored.
    """
    symbols = read_symbols(elf_symbols) if isinstance(elf_symbols, (str, Path)) else (elf_symbols or {})
    symbols = sorted((number(k), v) for k, v in symbols.items())

    def annotate(record):
        if record is None:
            return None
        record = dict(record)
        for address, name in reversed(symbols):
            if address <= record['pc']:
                record['symbol'] = f'{name}+0x{record["pc"] - address:x}'
                break
        return record

    def done(record):
        mem = record['mem']
        return mem is not None and mem['kind'] == 'store' and mem['width'] == 4 and mem['address'] == done_address and mem['data'] == done_value

    report = dict(passed=False, reason='completion-missing', compared=0,
                  spike=str(spike_path), rtl=str(rtl_path), entry=entry,
                  done_address=done_address, done_value=done_value)
    previous = deque(maxlen=3)
    spike = rtl = None
    left = right = None
    with ExitStack() as stack:
        try:
            spike = iter(spike_records(stack.enter_context(open(spike_path)), entry))
            rtl = iter(rtl_records(stack.enter_context(open(rtl_path))))
            while True:
                left = right = None
                left = next(spike, None)
                right = next(rtl, None)
                if left is None or right is None:
                    reason = 'completion-missing' if left is None and right is None else ('spike-truncated' if left is None else 'rtl-truncated')
                    raise TraceError(reason, 'log ended before the matched completion store')
                for field in ('pc', 'instruction', 'rd', 'value'):
                    if left[field] != right[field]:
                        raise TraceError(f'{field}-mismatch', f'{field}: Spike={left[field]:#x}, RTL={right[field]:#x}')
                if left['mem'] != right['mem']:
                    for field in ('kind', 'width', 'address', 'data'):
                        if (left['mem'] or {}).get(field) != (right['mem'] or {}).get(field):
                            raise TraceError(f'memory-{field}-mismatch', f'Spike={left["mem"]}, RTL={right["mem"]}')
                report['compared'] += 1
                previous.append(dict(spike=annotate(left), rtl=annotate(right)))
                if done(left):
                    extra = next(rtl, None)
                    if extra is not None:
                        right = extra
                        raise TraceError('rtl-after-completion', 'RTL log must end at the completion store')
                    report.update(passed=True, reason='matched-completion', epoch=right['epoch'], cycle=right['cycle'])
                    break
        except (TraceError, OSError) as error:
            report.update(reason=getattr(error, 'reason', 'io-error'), detail=str(error),
                          previous=list(previous), current=dict(spike=annotate(left), rtl=annotate(right)))
            following = {}
            for name, stream in (('spike', spike), ('rtl', rtl)):
                try:
                    following[name] = annotate(next(stream, None)) if stream is not None else None
                except (TraceError, ValueError) as next_error:
                    following[name] = dict(parse_error=str(next_error))
            report['next'] = following
    if output_path is not None:
        destination = Path(output_path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--spike', required=True)
    parser.add_argument('--rtl', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--symbols')
    parser.add_argument('--entry', type=lambda x: int(x, 0), default=0x80000000)
    parser.add_argument('--done-address', type=lambda x: int(x, 0), default=0x8021ff00)
    parser.add_argument('--done-value', type=lambda x: int(x, 0), default=1)
    args = parser.parse_args()
    result = compare_files(args.spike, args.rtl, args.output, entry=args.entry,
                           done_address=args.done_address, done_value=args.done_value,
                           elf_symbols=args.symbols)
    print(json.dumps({k: result[k] for k in ('passed', 'reason', 'compared')}))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
