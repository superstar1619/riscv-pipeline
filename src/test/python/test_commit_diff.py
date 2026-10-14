"""Executable fault-injection contract for the strict commit comparator."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('commit_diff', ROOT / 'scripts/lib/commit_diff.py')
diff = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diff)


def record(order, instruction, rd=0, value=0, mem=None):
    return dict(epoch=2, order=order, cycle=order + 8, pc=0x80000000 + order * 4,
                instruction=instruction, rd=rd, value=value, mem=mem, x0=0)


def good_records():
    return [record(0, 0x00100093, 1, 1),  # addi x1,x0,1
            record(1, 0x00200093, 1, 2),  # overwrite first result
            record(2, 0x00112023, mem=dict(kind='store', address=0x80210000, width=4, data=2)),
            record(3, 0x00012183, 3, 2, dict(kind='load', address=0x80210000, width=4)),
            record(4, 0x00112023, mem=dict(kind='store', address=0x8021ff00, width=4, data=1))]


def spike_text(records):
    lines = ['core   0: 3 0x00001000 (0x00000297) x5  0x00001000']
    for item in records:
        line = f'core   0: 3 0x{item["pc"]:08x} (0x{item["instruction"]:08x})'
        if item['rd']:
            line += f' x{item["rd"]} 0x{item["value"]:08x}'
        if item['mem']:
            line += f' mem 0x{item["mem"]["address"]:08x}'
            if item['mem']['kind'] == 'store':
                line += f' 0x{item["mem"]["data"]:08x}'
        lines.append(line)
    return '\n'.join(lines) + '\n'


class CommitDiffTests(unittest.TestCase):
    def compare(self, rtl=None, reference=None, spike_suffix='', **kwargs):
        rtl = good_records() if rtl is None else rtl
        reference = good_records() if reference is None else reference
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'rtl.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in rtl))
            (base / 'spike.log').write_text(spike_text(reference) + spike_suffix)
            report = diff.compare_files(base / 'spike.log', base / 'rtl.jsonl', base / 'diff.json',
                                        elf_symbols={0x80000000: '_start'}, **kwargs)
            self.assertEqual(report, json.loads((base / 'diff.json').read_text()))
            return report

    def reject(self, reason, **kwargs):
        report = self.compare(**kwargs)
        self.assertFalse(report['passed'])
        self.assertEqual(report['reason'], reason, report)
        return report

    def test_match_and_ignore_only_post_completion_spike_suffix(self):
        report = self.compare(spike_suffix='core 0: exception trap_after_host_exit\n')
        self.assertTrue(report['passed'], report)
        self.assertEqual(report['compared'], 5)
        self.assertEqual(report['epoch'], 2)

    def test_intermediate_error_even_when_later_overwritten(self):
        rtl = good_records()
        rtl[0]['value'] = 17
        report = self.reject('value-mismatch', rtl=rtl)
        self.assertEqual(report['compared'], 0)
        self.assertEqual(report['current']['rtl']['cycle'], 8)
        self.assertEqual(report['current']['rtl']['symbol'], '_start+0x0')
        self.assertEqual(report['next']['rtl']['value'], 2)

    def test_memory_mutations(self):
        for field in ('address', 'data'):
            with self.subTest(field=field):
                rtl = good_records()
                rtl[2]['mem'][field] ^= 4
                self.reject(f'memory-{field}-mismatch', rtl=rtl)
        rtl = good_records()
        rtl[3]['mem']['address'] += 4
        self.reject('memory-address-mismatch', rtl=rtl)

    def test_load_results_checked_by_writeback(self):
        rtl = good_records()
        rtl[3]['value'] = 0xffff0002
        self.reject('value-mismatch', rtl=rtl)

    def test_delete_duplicate_reorder_events(self):
        for variant in ('delete', 'duplicate', 'reorder'):
            rtl = good_records()
            if variant == 'delete':
                del rtl[1]
            elif variant == 'duplicate':
                rtl.insert(2, copy.deepcopy(rtl[1]))
            else:
                rtl[1], rtl[2] = rtl[2], rtl[1]
            with self.subTest(variant=variant):
                self.reject('rtl-order', rtl=rtl)
                # Changing sequence labels cannot make a missing event pass.
                for i, item in enumerate(rtl):
                    item.update(order=i, cycle=i + 8)
                self.reject('pc-mismatch', rtl=rtl)

    def test_pc_and_instruction_mutations(self):
        rtl = good_records()
        rtl[1]['pc'] += 8
        self.reject('pc-mismatch', rtl=rtl)
        rtl = good_records()
        rtl[1]['instruction'] ^= 1 << 20
        self.reject('instruction-mismatch', rtl=rtl)

    def test_reference_delete_duplicate_reorder_are_not_resynchronized(self):
        for variant in ('delete', 'duplicate', 'reorder'):
            reference = good_records()
            if variant == 'delete':
                del reference[1]
            elif variant == 'duplicate':
                reference.insert(2, copy.deepcopy(reference[1]))
            else:
                reference[1], reference[2] = reference[2], reference[1]
            with self.subTest(variant=variant):
                self.reject('pc-mismatch', reference=reference)

    def test_partial_json_record_and_unparsed_spike_suffix_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            (base / 'spike.log').write_text(spike_text(good_records()))
            (base / 'rtl.jsonl').write_text(json.dumps(good_records()[0]) + '\n{"epoch":')
            report = diff.compare_files(base / 'spike.log', base / 'rtl.jsonl')
            self.assertFalse(report['passed'])
            self.assertEqual(report['reason'], 'rtl-parse')
        lines = spike_text(good_records()).splitlines()
        lines[2] += ' unsupported-side-effect'
        with self.assertRaises(diff.TraceError) as caught:
            list(diff.spike_records(lines))
        self.assertEqual(caught.exception.reason, 'spike-parse')

    def test_truncation_and_missing_completion(self):
        self.reject('rtl-truncated', rtl=good_records()[:-1])
        self.reject('spike-truncated', reference=good_records()[:-1])
        self.reject('completion-missing', rtl=good_records()[:-1], reference=good_records()[:-1])
        rtl = good_records()
        rtl[-1]['mem']['data'] = 0
        self.reject('completion-missing', rtl=rtl, reference=rtl)

    def test_extra_rtl_event_after_completion(self):
        rtl = good_records() + [record(5, 0x0000006f)]
        self.reject('rtl-after-completion', rtl=rtl)

    def test_decode_width_independently(self):
        rtl = good_records()
        rtl[2]['mem']['width'] = 1
        self.reject('rtl-memory-width', rtl=rtl)
        for instruction, width in ((0x00110023, 1), (0x00111023, 2)):
            reference = good_records()
            reference[2].update(instruction=instruction)
            reference[2]['mem'].update(width=width, data=2)
            rtl = copy.deepcopy(reference)
            rtl[2]['mem']['data'] |= 0xffff << (width * 8)
            rtl[2]['mem']['data'] &= 0xffffffff
            self.assertTrue(self.compare(rtl=rtl, reference=reference)['passed'])

    def test_x0_write_normalization_and_architectural_invariant(self):
        reference = good_records()
        reference[0].update(instruction=0x00100013, rd=0, value=0)
        rtl = copy.deepcopy(reference)
        rtl[0]['value'] = 1  # ALU result for suppressed write is irrelevant.
        self.assertTrue(self.compare(rtl=rtl, reference=reference)['passed'])
        rtl[0]['x0'] = 1
        self.reject('x0-invariant', rtl=rtl, reference=reference)

    def test_reset_epoch_and_retirement_uniqueness(self):
        rtl = good_records()
        rtl[1]['epoch'] += 1
        self.reject('rtl-epoch', rtl=rtl)
        rtl = good_records()
        rtl[1]['cycle'] = rtl[0]['cycle']
        self.reject('rtl-cycle', rtl=rtl)

    def test_unexpected_trap_and_instruction(self):
        rtl = good_records()
        rtl[1]['trap'] = True
        self.reject('unexpected-trap', rtl=rtl)
        self.reject('unexpected-trap', reference=good_records()[:1],
                    spike_suffix='core 0: exception trap_illegal_instruction\n')
        rtl = good_records()
        rtl[1].update(instruction=0x00000073, rd=0, value=0)
        self.reject('unexpected-instruction', rtl=rtl)

    def test_no_resync_or_arbitrary_prefix_removal(self):
        lines = spike_text(good_records()).splitlines()
        lines[0] = lines[0].replace('0x00001000', '0x70001000', 1)
        with self.assertRaisesRegex(diff.TraceError, 'non-ROM PC'):
            list(diff.spike_records(lines))
        lines = spike_text(good_records()).splitlines()
        lines[2] = lines[2].replace(' x1 ', ' x 1 ')
        self.assertEqual(len(list(diff.spike_records(lines))), 5)


if __name__ == '__main__':
    unittest.main()
