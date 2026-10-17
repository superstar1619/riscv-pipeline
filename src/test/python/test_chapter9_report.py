"""Report acceptance regressions; synthetic fixtures are not production RTL evidence."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]


def load_report():
    spec = importlib.util.spec_from_file_location(
        'chapter9_report_regression', ROOT / 'scripts/report/report-chapter9.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


class Chapter9ReportTests(unittest.TestCase):
    def setUp(self):
        self.report = load_report()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        # Registering these deliberately synthetic fixtures as real runs is forbidden.
        self.report.register_report = lambda *args, **kwargs: None

    def rows(self):
        return [dict(name=case, config=f'{mode}-ram{latency}', seed=seed,
                     repeat=repeat, status='pass', exit=0,
                     evidence=str(self.directory / 'foreign-cached.json'),
                     trace=str(self.directory / 'foreign-trace.jsonl'))
                for case in ('memory-switch', 'apb-subword',
                             'privilege-endian', 'interrupt-precise')
                for mode in ('bus-only', 'mixed') for latency in (0, 1, 2)
                for seed in (1, 17, 2026) for repeat in (0, 1)]

    def source_fixture(self):
        # Positive fixture verifies archived bytes, not an asserted cached pass flag.
        paths = ['scripts/lib/cvw_common.py', 'scripts/lib/commit_diff.py',
                 'scripts/lib/report_catalog.py', 'scripts/run/test-chapter9-rtl.py',
                 'scripts/report/report-chapter9.py',
                 'src/test/scala/riscvpipeline/Chapter9Observer.scala']
        hashes = {}
        for relative in paths:
            destination = self.directory / 'source' / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
            hashes[relative] = hashlib.sha256(destination.read_bytes()).hexdigest()
        write_json(self.directory / 'source-before.json', hashes)
        write_json(self.directory / 'source-after.json', hashes)
        write_json(self.directory / 'source-stability.json', dict(passed=True))
        return hashes

    def coverage_fixture(self):
        rows = []
        for config in sorted(self.report.CONFIGS):
            files = []
            for case in self.report.CASES:
                for seed in (1, 17, 2026):
                    path = self.directory / 'rtl' / config / case / f'coverage-s{seed}.dat'
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text('synthetic raw artifact\n')
                    files.append(str(path))
            work = self.directory / 'coverage' / config
            work.mkdir(parents=True)
            (work / 'coverage.info').write_text('DA:1,1\n')
            (work / 'coverage.dat').write_text('v_toggle synthetic 1\n')
            rows.append(dict(config=config, status='pass', files=files,
                             linePoints=1, hitLinePoints=1, togglePoints=1, hitTogglePoints=1))
        write_json(self.directory / 'code-coverage.json', rows)
        return rows

    def test_rejects_144_rows_reusing_foreign_cached_pass_without_real_evidence(self):
        rows = self.rows()
        write_json(self.directory / 'manifest.json', dict(requiredRuns=rows))
        write_json(self.directory / 'results.json', rows)
        # The old implementation accepted this minimal cached shape for every row.
        (self.directory / 'foreign-cached.json').write_text('{"check":{"pass":true}}')
        self.assertFalse(self.report.report(self.directory))
        summary = json.loads((self.directory / 'summary.json').read_text())
        self.assertTrue(summary['fullMatrix'])
        errors = [e for e in summary['evidenceErrors'] if 'key' in e]
        self.assertEqual(len(errors), 144)
        self.assertTrue(all('foreign or stale row path' in e['reason'] for e in errors))

    def test_valid_source_fixture_and_missing_acceptance_helper(self):
        hashes = self.source_fixture()
        checker = self.report.source_check(self.directory)
        self.assertTrue(callable(checker.check_trace))
        del hashes['scripts/lib/commit_diff.py']
        write_json(self.directory / 'source-before.json', hashes)
        write_json(self.directory / 'source-after.json', hashes)
        with self.assertRaisesRegex(ValueError, 'required source/helper snapshot absent'):
            self.report.source_check(self.directory)

    def test_rejects_archived_source_bytes_changed_after_hashing(self):
        self.source_fixture()
        path = self.directory / 'source/scripts/lib/cvw_common.py'
        path.write_text(path.read_text() + '\n# synthetic corruption\n')
        with self.assertRaisesRegex(ValueError, 'archived source hash mismatch'):
            self.report.source_check(self.directory)

    def test_python_platform_command_must_exist_once_and_succeed(self):
        path = self.directory / 'commands.jsonl'
        record = dict(key='python-platform', exit=0)
        path.write_text(json.dumps(record) + '\n')
        self.assertEqual(self.report.command_check(self.directory), [record])
        for records in ([], [dict(key='python-platform', exit=1)], [record, record]):
            path.write_text(''.join(json.dumps(r) + '\n' for r in records))
            with self.assertRaisesRegex(ValueError, 'Python platform command missing/failed'):
                self.report.command_check(self.directory)

    def test_valid_coverage_fixture_then_missing_raw_artifact(self):
        rows = self.coverage_fixture()
        self.assertEqual(self.report.coverage_check(self.directory), rows)
        Path(rows[0]['files'][0]).unlink()
        with self.assertRaisesRegex(ValueError, 'empty/missing coverage artifact'):
            self.report.coverage_check(self.directory)

    def test_rejects_coverage_cached_counts_that_disagree_with_artifacts(self):
        rows = self.coverage_fixture()
        rows[0]['hitTogglePoints'] = 0
        write_json(self.directory / 'code-coverage.json', rows)
        with self.assertRaisesRegex(ValueError, 'coverage counts differ from raw artifacts'):
            self.report.coverage_check(self.directory)

    def test_rejects_actual_events_from_wrong_reset_even_at_canonical_path(self):
        work = self.directory / 'rtl/bus-only-ram0/memory-switch'
        evidence = work / 'actual-s1-r0.json'
        trace = work / 'trace-s1.jsonl'
        row = dict(name='memory-switch', config='bus-only-ram0', seed=1, repeat=0,
                   status='pass', exit=0, evidence=str(evidence), trace=str(trace))
        write_json(self.directory / 'manifest.json', dict(requiredRuns=[row]))
        write_json(self.directory / 'results.json', [row])
        write_json(evidence, dict(events=[dict(repeat=1, cycle=1, kind='end')],
                                 check={'pass': True}))
        # Stub only transport parsing to isolate the report's reset-identity binding.
        checker = SimpleNamespace(read_trace=lambda path: (
            [dict(repeat=0, cycle=1, kind='end')], ''))
        with patch.object(self.report, 'source_check', return_value=checker), \
             patch.object(self.report, 'command_check', return_value=[]), \
             patch.object(self.report, 'spike_check', return_value={}):
            self.assertFalse(self.report.report(self.directory))
        errors = json.loads((self.directory / 'summary.json').read_text())['evidenceErrors']
        self.assertTrue(any('actual events differ from raw trace/reset identity' in e['reason']
                            for e in errors))


if __name__ == '__main__':
    unittest.main()
