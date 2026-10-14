"""Contract tests for the common scheduler; no simulator prerequisites."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

PATH = Path(__file__).resolve().parents[3] / 'scripts/verify.py'
spec = importlib.util.spec_from_file_location('verification', PATH)
verify = importlib.util.module_from_spec(spec)
if PATH.exists():
    spec.loader.exec_module(verify)


class VerificationContractTests(unittest.TestCase):
    def test_full_runs_scala_and_validation_once(self):
        stages = verify.stage_plan('all', 'full')
        self.assertEqual(stages.count('scala'), 1)
        self.assertEqual(stages.count('validation'), 1)
        self.assertEqual(set(stages), {'privileged', 'scala', 'privileged_coverage', 'rtl', 'validation', 'cvw', 'diff', 'python'})

    def test_smoke_contains_required_suites(self):
        self.assertEqual(verify.stage_plan('all', 'smoke'), ['privileged', 'scala', 'privileged_coverage', 'validation', 'diff'])
        self.assertEqual(verify.SMOKE_ARCH, ['addi-01', 'jalr-01', 'lw-align-01'])
        self.assertEqual(verify.SMOKE_PRIV, ['EX02', 'PR01', 'CS01', 'WFWAKE'])

    def test_privileged_suite_includes_actual_event_sampling(self):
        self.assertEqual(verify.stage_plan('privileged', 'full'), ['privileged', 'scala', 'privileged_coverage'])
        self.assertEqual(verify.stage_plan('privileged', 'smoke'), ['privileged', 'scala', 'privileged_coverage'])

    def test_counts_distinguish_cases_configs_and_runs(self):
        rows = [dict(suite='arch', name='add', config=c, seed=s, repeat=r, status='pass')
                for c in ['a', 'b'] for s in [1, 17] for r in [0, 1]]
        rows += [dict(suite='entrypoint', name='build', counted=False, status='pass')]
        counts = verify.count_results(rows)
        self.assertEqual((counts['independentCases'], counts['configurationPairs'], counts['runs']), (1, 2, 8))

    def test_expected_evidence_missing_duplicate_and_raw_exit_fail(self):
        expected = [dict(suite='arch', name='addi', config='default', seed=1)]
        self.assertEqual(verify.reconcile_results([], expected)[0]['status'], 'not_executed')
        row = dict(expected[0], status='pass')
        self.assertTrue(all(r['status'] == 'build_platform_error' for r in verify.reconcile_results([row, row], expected)))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'commands.jsonl').write_text('{"log":"case.log","exit":2,"argv":["sim"]}\n')
            actual = verify.attach_commands([dict(row, log='case.log')], root)[0]
        self.assertEqual(actual['exit'], 2)
        self.assertEqual(actual['status'], 'build_platform_error')

    def test_no_empty_evidence_passes_and_not_applicable_needs_reason(self):
        self.assertFalse(verify.accepted([]))
        self.assertFalse(verify.accepted([dict(status='cpu_failure', resolved_by='missing')]))
        self.assertFalse(verify.accepted([dict(status='not_applicable')]))
        self.assertFalse(verify.accepted([dict(status='not_executed', reason='tool unavailable')]))
        self.assertTrue(verify.accepted([dict(status='not_applicable', reason='MMU is not implemented')]))

    def test_reproduction_preserves_case_working_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'commands.jsonl').write_text('{"log":"case.log","exit":0,"argv":["sim","--arg"],"cwd":"/tmp/case dir"}\n')
            row = verify.attach_commands([dict(status='pass', log='case.log')], root)[0]
        self.assertEqual(row['reproduceOriginal'], "cd '/tmp/case dir' && sim --arg")

    def test_case_id_is_preserved(self):
        item = verify.case_entry('privileged', 'EX02', 'default', chapter=8)
        self.assertEqual(item['id'], 'EX02')
        self.assertIn('coveragePoints', item)
        self.assertIn('completion', item)
        self.assertIn('cycleLimit', item)


class SimulatorExitConsistencyTests(unittest.TestCase):
    def test_two_passing_resets_with_nonzero_exit_fail(self):
        from cvw_common import process_exit_results
        rows = process_exit_results([dict(repeat=0, status='pass'), dict(repeat=1, status='pass')], 1)
        self.assertEqual([r['status'] for r in rows], ['build_platform_error'] * 2)
        self.assertTrue(all(r['exit'] == 1 and r['evidenceStatus'] == 'pass' for r in rows))
        self.assertFalse(verify.accepted(rows))

    def test_first_pass_second_failure_preserved(self):
        from cvw_common import process_exit_results
        rows = process_exit_results([dict(repeat=0, status='pass'), dict(repeat=1, status='cpu_failure')], 1)
        self.assertEqual([r['status'] for r in rows], ['pass', 'cpu_failure'])
        self.assertFalse(verify.accepted(rows))

    def test_attached_command_checks_process_group_not_negative_or_first_reset(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'commands.jsonl').write_text('{"log":"case.log","exit":1,"argv":["sim"]}\n')
            passed = dict(suite='existing-rtl', status='pass', log='case.log')
            rows = verify.attach_commands([dict(passed, repeat=r) for r in (0, 1)], root)
            self.assertFalse(verify.accepted(rows))
            partial = verify.attach_commands([passed, dict(passed, status='cpu_failure')], root)
            self.assertEqual([r['status'] for r in partial], ['pass', 'cpu_failure'])
            negative = verify.attach_commands([dict(passed, suite='cvw-checker-negative')], root)
            self.assertTrue(verify.accepted(negative))
