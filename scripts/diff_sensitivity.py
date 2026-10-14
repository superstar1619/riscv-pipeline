"""Stream fault mutations of an actual, matched Spike/RTL trace pair.

This tests the comparator, not the CPU: the original execution and its final
signature stay unchanged. Mutation evidence records the exact expected reason.
"""
import json
from pathlib import Path

from commit_diff import compare_files, number


def _records(path):
    with Path(path).open() as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def _write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def _targets(path):
    register = store = control = completion = overwrite = None
    count = 0
    for index, item in enumerate(_records(path)):
        rd = number(item['rd'])
        if register is None and rd:
            register = dict(index=index, rd=rd, order=number(item['order']))
        elif register is not None and overwrite is None and rd == register['rd']:
            overwrite = number(item['order'])
        if control is None and number(item['instruction']) & 0x7f in (0x63, 0x67, 0x6f):
            control = index
        mem = item['mem']
        if mem and mem['kind'] == 'store':
            if number(mem['address']) == 0x8021ff00 and number(mem['data']) == 1:
                completion = index
            elif store is None:
                store = index
        count += 1
    if register is None or overwrite is None or store is None or completion is None or count < 3:
        raise ValueError('baseline needs an overwritten register, ordinary store, completion and three commits')
    return dict(register=register['index'], registerNumber=register['rd'], overwriteOrder=overwrite,
                store=store, pc=control if control is not None else 1, completion=completion, count=count)


def _mutated(path, name, target):
    """Yield at most one buffered record; never materialize an input trace."""
    held = None
    for index, item in enumerate(_records(path)):
        if name == 'register-overwritten' and index == target['register']:
            item['value'] = number(item['value']) ^ 1
        elif name == 'pc' and index == target['pc']:
            item['pc'] = number(item['pc']) ^ 4
        elif name in ('store-address', 'store-data') and index == target['store']:
            field = name.removeprefix('store-')
            item['mem'][field] = number(item['mem'][field]) ^ (4 if field == 'address' else 1)
        elif name.startswith('event-delete') and index == 1:
            continue
        elif name.startswith('event-duplicate') and index == 1:
            yield dict(item)
        elif name.startswith('event-reorder'):
            if index == 1:
                held = item
                continue
            if index == 2:
                yield item
                yield held
                held = None
                continue
        elif name == 'truncated-file' and index == target['count'] // 2:
            return
        elif name == 'missing-completion' and index == target['completion']:
            continue
        yield item


def exercise(spike_path, rtl_path, outdir, symbols=None):
    """Return sensitivity result rows, retaining mutated logs and diff reports.

    The destination must be fresh. A baseline mismatch is a failing row, never
    evidence that the injected faults were detected. Sequence corruption is
    tested both with original labels and with labels renumbered to conceal it.
    """
    outdir = Path(outdir)
    outdir.mkdir(parents=True, exist_ok=False)
    baseline_path = outdir / 'baseline.diff.json'
    baseline = compare_files(spike_path, rtl_path, baseline_path, elf_symbols=symbols)
    if not baseline['passed']:
        rows = [dict(name='baseline', status='cpu_failure', reason=baseline['reason'],
                     expectedReason='matched-completion', log=str(rtl_path), diff=str(baseline_path))]
        _write_json(outdir/'results.json', rows)
        _write_json(outdir/'summary.json', dict(passed=False, mutations=0, spike=str(spike_path),
                    rtl=str(rtl_path), baseline=str(baseline_path), reason=rows[0]['reason']))
        return rows
    try:
        target = _targets(rtl_path)
    except ValueError as error:
        rows = [dict(name='sensitivity-input', status='build_platform_error', reason=str(error),
                     expectedReason='required mutation targets present', log=str(rtl_path), diff=str(baseline_path))]
        _write_json(outdir/'results.json', rows)
        _write_json(outdir/'summary.json', dict(passed=False, mutations=0, spike=str(spike_path),
                    rtl=str(rtl_path), baseline=str(baseline_path), reason=rows[0]['reason']))
        return rows
    variants = [('register-overwritten', 'value-mismatch'), ('pc', 'pc-mismatch'),
                ('store-address', 'memory-address-mismatch'), ('store-data', 'memory-data-mismatch'),
                ('event-delete', 'rtl-order'), ('event-duplicate', 'rtl-order'), ('event-reorder', 'rtl-order'),
                ('event-delete-renumbered', 'pc-mismatch'), ('event-duplicate-renumbered', 'pc-mismatch'),
                ('event-reorder-renumbered', 'pc-mismatch'), ('truncated-json', 'rtl-parse'),
                ('truncated-file', 'rtl-truncated'), ('missing-completion', 'rtl-truncated')]
    rows = []
    for name, expected in variants:
        log, report_path = outdir/(name+'.jsonl'), outdir/(name+'.diff.json')
        with log.open('w') as stream:
            for index, item in enumerate(_mutated(rtl_path, name, target)):
                if name == 'truncated-json' and index == target['store']:
                    stream.write('{"epoch":')
                    break
                if name.endswith('-renumbered'):
                    item.update(order=index, cycle=index + 1)
                stream.write(json.dumps(item, separators=(',', ':')) + '\n')
        report = compare_files(spike_path, log, report_path, elf_symbols=symbols)
        row = dict(name=name, status='pass' if not report['passed'] and report['reason'] == expected else 'cpu_failure',
                   reason=report['reason'], expectedReason=expected, log=str(log), diff=str(report_path),
                   compared=report['compared'])
        if name == 'register-overwritten':
            row.update(register=target['registerNumber'], mutationOrder=target['register'],
                       overwriteOrder=target['overwriteOrder'])
        rows.append(row)
        _write_json(outdir/'results.json', rows)
    _write_json(outdir/'summary.json', dict(passed=all(r['status'] == 'pass' for r in rows),
        mutations=len(rows), spike=str(spike_path), rtl=str(rtl_path), baseline=str(baseline_path), targets=target,
        note='Only trace evidence is mutated; original CPU execution, later writes and final signature are retained.'))
    return rows
