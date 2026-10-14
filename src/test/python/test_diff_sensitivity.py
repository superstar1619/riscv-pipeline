"""Sensitivity evidence must come from matched input traces and exact rejections."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from test_commit_diff import good_records, spike_text


class DiffSensitivityTests(unittest.TestCase):
    def exercise(self, records=None, reference=None):
        import diff_sensitivity
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        base = Path(temp.name)
        records = good_records() if records is None else records
        reference = good_records() if reference is None else reference
        (base/'rtl.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records))
        (base/'spike.log').write_text(spike_text(reference))
        rows = diff_sensitivity.exercise(base/'spike.log', base/'rtl.jsonl', base/'mutations',
                                         symbols={0x80000000: '_start'})
        return base, rows

    def test_real_input_mutations_reject_for_expected_reasons(self):
        base, rows = self.exercise()
        required = {'register-overwritten', 'pc', 'store-address', 'store-data',
                    'event-delete', 'event-duplicate', 'event-reorder',
                    'truncated-json', 'truncated-file', 'missing-completion'}
        self.assertTrue(required <= {r['name'] for r in rows}, rows)
        self.assertTrue(all(r['status'] == 'pass' and r['reason'] == r['expectedReason'] for r in rows), rows)
        self.assertEqual(json.loads((base/'mutations/results.json').read_text()), rows)
        for row in rows:
            self.assertTrue(Path(row['log']).is_file())
            report = json.loads(Path(row['diff']).read_text())
            self.assertFalse(report['passed'])
            self.assertEqual(report['reason'], row['expectedReason'])
        register = next(r for r in rows if r['name'] == 'register-overwritten')
        self.assertEqual(register['overwriteOrder'], 1)
        mutated = [json.loads(line) for line in Path(register['log']).read_text().splitlines()]
        self.assertNotEqual(mutated[0]['value'], good_records()[0]['value'])
        self.assertEqual(mutated[1:], good_records()[1:])

    def test_reject_unmatched_baseline_without_claiming_mutation_success(self):
        records = good_records()
        records[0]['value'] ^= 1
        base, rows = self.exercise(records)
        self.assertFalse(json.loads((base/'mutations/summary.json').read_text())['passed'])
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['name'], 'baseline')
        self.assertEqual(rows[0]['status'], 'cpu_failure')
        self.assertEqual(rows[0]['reason'], 'value-mismatch')

    def test_missing_overwrite_target_is_not_silently_skipped(self):
        records = good_records()
        records[1].update(instruction=0x00200113, rd=2)
        base, rows = self.exercise(records, records)
        self.assertEqual(rows[0]['status'], 'build_platform_error')
        self.assertIn('overwritten register', rows[0]['reason'])
        self.assertFalse(json.loads((base/'mutations/summary.json').read_text())['passed'])

    def test_existing_evidence_directory_is_never_overwritten(self):
        import diff_sensitivity
        base, _ = self.exercise()
        before = (base/'mutations/results.json').read_bytes()
        with self.assertRaises(FileExistsError):
            diff_sensitivity.exercise(base/'spike.log', base/'rtl.jsonl', base/'mutations')
        self.assertEqual((base/'mutations/results.json').read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
