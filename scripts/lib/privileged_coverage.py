#!/usr/bin/env python3
"""Merge actual chapter-8 Scala event bins; failed runs never satisfy coverage."""
import argparse
import json
from pathlib import Path

SMOKE_IDS = {'EX02', 'PR01', 'CS01', 'WFWAKE'}
CONFIGS = {
    'default': dict(supervisor=True, bigEndian=True, sstc=False, hpm=False),
    'minimal': dict(supervisor=False, bigEndian=False, sstc=False, hpm=False),
    'sstc-hpm': dict(supervisor=True, bigEndian=True, sstc=True, hpm=True),
    'reset100': dict(supervisor=True, bigEndian=True, sstc=False, hpm=False),
}
INVENTORY = Path(__file__).resolve().parents[2] / 'programs/verification/chapter8-required.json'


def required_bins(config, profile):
    capability = CONFIGS[config]
    bins = {'privilege.mode3', 'trap.cause2.from3.to3.interrupt0',
            'wfi.stall.mode3.pending0', 'wfi.wake.mode3.pending1',
            'interrupt.pending1.global0.accepted0.mode3',
            'interrupt.arrival.wfi',
            'memory.little.store.width4.offset0'}
    bins.update(f'csr.op{op}' for op in (1, 2, 3, 5, 6, 7))
    bins.update(f'csr.op{op}.source_zero' for op in (2, 6, 7))
    bins.update(f'csr.op{op}.rd_zero' for op in (1, 7))
    bins.update('instruction.' + kind for kind in ('alu', 'lui', 'store', 'csr', 'system', 'jal'))
    if profile == 'smoke':
        return sorted(bins)
    modes = [0, 1, 3] if capability['supervisor'] else [0, 3]
    bins.update(f'privilege.mode{mode}' for mode in modes)
    bins.update(f'wfi.stall.mode{mode}.pending0' for mode in modes)
    bins.update({'stall.load_use.pending0', 'stall.load_use.pending1', 'interrupt.arrival.load_stall', 'interrupt.after_wfi.mode3',
                 'interrupt.pending1.global1.accepted1.mode3', 'instruction.load'})
    for cause in (0, 1, 2, 3, 4, 5, 6, 7, 11):
        bins.add(f'trap.cause{cause}.from3.to3.interrupt0')
    bins.add('trap.cause8.from0.to3.interrupt0')
    bins.add('trap.cause2.from0.to3.interrupt0')
    for cause in (3, 7, 11):
        bins.add(f'trap.cause{cause}.from3.to3.interrupt1')
    if capability['supervisor']:
        bins.update({'trap.cause9.from1.to3.interrupt0', 'trap.cause2.from1.to3.interrupt0',
                     'interrupt.pending1.global0.accepted1.mode0', 'interrupt.after_wfi.mode1'})
        for cause in (1, 5, 9):
            bins.add(f'trap.cause{cause}.from3.to3.interrupt1')
            bins.add(f'trap.cause{cause}.from1.to1.interrupt1')
    for endian in ('little', 'big') if capability['bigEndian'] else ('little',):
        for kind in ('load', 'store'):
            for width in (1, 2, 4):
                bins.update(f'memory.{endian}.{kind}.width{width}.offset{offset}'
                            for offset in range(0, 4, width))
    for mode in modes:
        bins.add(f'memory.mode{mode}.{"big" if capability["bigEndian"] else "little"}')
    return sorted(bins)


def mapping(bin_id):
    if bin_id.startswith(('csr.', 'instruction.csr')):
        return dict(book='§8.3 表8.6 pp.360–366', stimulus='CS01/CS03/permission cases',
                    checker='ordered CSR old-value, WARL and exception signatures')
    if bin_id.startswith('memory.'):
        return dict(book='§8.3 表8.7 pp.360–366', stimulus='ENWIDTH/EN*',
                    checker='ordered CPU-load values, byte masks, preserved neighboring bytes')
    if bin_id.startswith(('wfi.', 'interrupt.')):
        return dict(book='§8.3 表8.5/8.9 pp.360–366', stimulus='WF*/IR*/STIRQ',
                    checker='ordered trap EPC/cause/status/pending and completion stores')
    if bin_id.startswith('trap.'):
        return dict(book='§8.3 表8.8/8.9 pp.360–366', stimulus='EX*/PR*/CS*/IR*',
                    checker='ordered trap signatures and cancelled younger side effects')
    return dict(book='§8.3 pp.360–366', stimulus='chapter-8 applicable fixtures',
                checker='ordered stores and age-aware valid/write/reset assertions')


def validate_bin(bin_id, record):
    if not isinstance(record, dict) or type(record.get('count')) is not int or record['count'] < 1:
        raise ValueError(f'{bin_id}: positive integer count required')
    proof = record.get('first')
    if not isinstance(proof, dict) or type(proof.get('cycle')) is not int or proof['cycle'] < 0:
        raise ValueError(f'{bin_id}: missing actual cycle evidence')
    for key in ('retired', 'trap', 'interrupt', 'wfiStall', 'loadStall', 'validM'):
        if type(proof.get(key)) is not bool:
            raise ValueError(f'{bin_id}: missing boolean event {key}')
    int(proof['pc']); instruction = int(proof['instruction'])
    opcode = instruction & 127
    mode, target = proof['mode'], proof['targetMode']
    if type(mode) is not int or mode not in (0, 1, 3) or target not in (0, 1, 3):
        raise ValueError(f'{bin_id}: invalid privilege mode')
    pending = int(int(proof['pending']) != 0)
    status = int(proof['status'])
    global_enable = (status >> (3 if mode == 3 else 1)) & 1 if mode != 0 else 0
    if proof['globalEnable'] != global_enable:
        raise ValueError(f'{bin_id}: global enable disagrees with actual status/mode')
    if (proof['retired'] or proof['trap']) and not proof['validM']:
        raise ValueError(f'{bin_id}: invalid M instruction cannot retire or trap')
    if proof['retired'] and proof['trap']:
        raise ValueError(f'{bin_id}: trap and normal retirement are mutually exclusive')
    if proof['interrupt'] and not proof['trap']:
        raise ValueError(f'{bin_id}: accepted interrupt requires trap')
    expected = None
    if bin_id.startswith('stall.') or bin_id == 'interrupt.arrival.load_stall':
        if not proof['loadStall'] or proof.get('loadInE') is not True:
            raise ValueError(f'{bin_id}: stall without actual hazard')
        expected = f'stall.load_use.pending{pending}' if bin_id.startswith('stall.') else bin_id
    elif bin_id.startswith('wfi.stall.'):
        if not proof['wfiStall'] or instruction != 0x10500073:
            raise ValueError(f'{bin_id}: no held WFI')
        expected = f'wfi.stall.mode{mode}.pending{pending}'
    elif not proof['retired'] and not proof['trap']:
        raise ValueError(f'{bin_id}: no architectural event')
    if bin_id.startswith('trap.'):
        expected = f'trap.cause{int(proof["cause"])}.from{proof["mode"]}.to{proof["targetMode"]}.interrupt{int(proof["interrupt"])}'
        if not proof['trap'] or bin_id != expected:
            raise ValueError(f'{bin_id}: trap evidence mismatch')
    if bin_id.startswith('interrupt.pending'):
        expected = (f'interrupt.pending{pending}.global{global_enable}'
                    f'.accepted{int(proof["interrupt"])}.mode{mode}')
        if bin_id != expected:
            raise ValueError(f'{bin_id}: interrupt status evidence mismatch')
    if bin_id.startswith('interrupt.arrival.'):
        if int(proof['previousPending']) != 0 or not pending:
            raise ValueError(f'{bin_id}: no actual pending arrival')
        if bin_id == 'interrupt.arrival.wfi':
            if instruction != 0x10500073 or not proof['validM']:
                raise ValueError(f'{bin_id}: pending did not arrive at WFI')
            expected = bin_id
    if bin_id.startswith('interrupt.after_wfi.') and not (
            proof['interrupt'] and proof['trap'] and proof.get('previousRetiredWasWfi') is True):
        raise ValueError(f'{bin_id}: no actual WFI/interrupt boundary')
    if bin_id.startswith('interrupt.after_wfi.'):
        expected = f'interrupt.after_wfi.mode{mode}'
    if bin_id.startswith('wfi.wake.') and not (
            proof['retired'] and instruction == 0x10500073 and int(proof['pending']) != 0):
        raise ValueError(f'{bin_id}: no actual pending WFI wake')
    if bin_id.startswith('wfi.wake.'):
        expected = f'wfi.wake.mode{mode}.pending1'
    if bin_id.startswith('privilege.'):
        expected = f'privilege.mode{mode}'
    if bin_id.startswith(('instruction.', 'csr.', 'memory.')) and not proof['retired']:
        raise ValueError(f'{bin_id}: speculative instruction cannot count')
    if bin_id.startswith('csr.'):
        operation = (instruction >> 12) & 7
        if opcode != 115 or operation not in (1, 2, 3, 5, 6, 7):
            raise ValueError(f'{bin_id}: CSR opcode mismatch')
        expected = f'csr.op{operation}'
        if bin_id.endswith('.source_zero') and ((instruction >> 15) & 31):
            raise ValueError(f'{bin_id}: CSR source is nonzero')
        if bin_id.endswith('.rd_zero') and ((instruction >> 7) & 31):
            raise ValueError(f'{bin_id}: CSR rd is nonzero')
        if bin_id.endswith('.source_zero'):
            expected += '.source_zero'
        elif bin_id.endswith('.rd_zero'):
            expected += '.rd_zero'
    if bin_id.startswith('instruction.'):
        kind = {3:'load', 35:'store', 99:'branch', 103:'jalr', 111:'jal',
                19:'alu', 51:'alu', 55:'lui', 23:'auipc', 15:'fence',
                115:'csr' if (instruction >> 12) & 7 else 'system'}.get(opcode)
        if kind is None:
            raise ValueError(f'{bin_id}: unsupported retired opcode')
        expected = 'instruction.' + kind
    if bin_id.startswith('memory.'):
        if opcode not in (3, 35):
            raise ValueError(f'{bin_id}: no memory instruction')
        if proof['endian'] not in ('little', 'big'):
            raise ValueError(f'{bin_id}: invalid effective endian')
        if int(proof['memRW']) != (2 if opcode == 3 else 1):
            raise ValueError(f'{bin_id}: no actual matching memory request')
        if bin_id.startswith('memory.mode'):
            expected = f'memory.mode{mode}.{proof["endian"]}'
        else:
            expected = f'memory.{proof["endian"]}.{"load" if opcode == 3 else "store"}.width{1 << ((instruction >> 12) & 3)}.offset{int(proof["address"]) % 4}'
            if bin_id != expected:
                raise ValueError(f'{bin_id}: memory evidence mismatch')
    if expected is None or bin_id != expected:
        raise ValueError(f'{bin_id}: label does not match actual event {expected}')


def aggregate(directory, profile):
    directory = Path(directory)
    report = dict(passed=False, profile=profile, sampling='M retirement/trap; actual load-use/WFI hazards',
                  configurations={}, failures=[], failedRuns=[], excluded=[], passingRuns=0)
    try:
        cases = json.loads((directory / 'cases.json').read_text())
        if not isinstance(cases, list) or not cases:
            raise ValueError('nonempty exported cases.json required')
        selected = [case for case in cases if profile == 'full' or
                    (case['config'] == 'default' and case['id'] in SMOKE_IDS)]
        actual_configs = {case['config'] for case in selected}
        expected_configs = set(CONFIGS) if profile == 'full' else {'default'}
        if actual_configs != expected_configs:
            raise ValueError(f'configuration inventory {actual_configs} != {expected_configs}')
        if profile == 'smoke' and {case['id'] for case in selected} != SMOKE_IDS:
            raise ValueError('smoke fixture inventory incomplete')
        inventory = json.loads(INVENTORY.read_text())['configurations']
        if profile == 'full':
            for config in expected_configs:
                actual_ids = {case['id'] for case in selected if case['config'] == config}
                if actual_ids != set(inventory[config]):
                    raise ValueError(f'{config}: required fixture inventory differs; missing={sorted(set(inventory[config]) - actual_ids)}, extra={sorted(actual_ids - set(inventory[config]))}')
        seen = set()
        for config in sorted(expected_configs):
            required = required_bins(config, profile)
            report['configurations'][config] = dict(capabilities=CONFIGS[config], bins={}, required=required,
                missing=[], mapping={key: mapping(key) for key in required})
            if not CONFIGS[config]['supervisor']:
                report['excluded'].append(dict(config=config, family='S mode, delegated S traps and IRQ',
                                                reason='supervisorSupported=false'))
            if not CONFIGS[config]['bigEndian']:
                report['excluded'].append(dict(config=config, family='big-endian memory',
                                                reason='bigEndianSupported=false; WARL fields read zero'))
        for case in selected:
            config, key = case['config'], case['key']
            if key != config + '-' + case['id']:
                raise ValueError(f'invalid fixture key {key}')
            if key in seen:
                raise ValueError(f'duplicate fixture {key}')
            seen.add(key)
            for repeat in (0, 1):
                path = directory / 'scala' / f'{key}-r{repeat}.json'
                try:
                    result = json.loads(path.read_text())
                    if result.get('key') != key or result.get('repeat') != repeat or result.get('seed') != 1:
                        raise ValueError('case/reset identity mismatch')
                    bins = result.get('functionalCoverage')
                    if not isinstance(bins, dict) or not bins:
                        raise ValueError('nonempty actual functionalCoverage required')
                    for bin_id, value in bins.items():
                        validate_bin(bin_id, value)
                    if result.get('pass') is not True:
                        report['failedRuns'].append(dict(case=key, repeat=repeat, evidence=str(path), bins=bins))
                        raise ValueError('failed run is excluded from passing coverage')
                    merged = report['configurations'][config]['bins']
                    for bin_id, value in bins.items():
                        if bin_id not in merged:
                            merged[bin_id] = dict(count=0, first=dict(case=key, repeat=repeat,
                                seed=1, file=str(path), **value['first']))
                        merged[bin_id]['count'] += value['count']
                    report['passingRuns'] += 1
                except (OSError, ValueError, KeyError, TypeError) as error:
                    report['failures'].append(dict(case=key, repeat=repeat, evidence=str(path), reason=str(error)))
        for config, data in report['configurations'].items():
            data['missing'] = sorted(set(data['required']) - set(data['bins']))
        report['passed'] = not report['failures'] and all(not d['missing'] for d in report['configurations'].values())
    except (OSError, ValueError, KeyError, TypeError) as error:
        report['failures'].append(dict(reason=str(error)))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--profile', choices=('smoke', 'full'), required=True)
    args = parser.parse_args()
    result = aggregate(args.directory, args.profile)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(dict(passed=result['passed'], passingRuns=result['passingRuns'],
        missing={key: value['missing'] for key, value in result['configurations'].items()}, failures=result['failures'])))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
