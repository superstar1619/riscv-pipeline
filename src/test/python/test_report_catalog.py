"""Report navigation never changes docs or turns missing evidence into a pass."""
from pathlib import Path
import importlib.util
import json
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/lib'))
try:
    import report_catalog as catalog
except ModuleNotFoundError:
    catalog = None


class ReportCatalogTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(catalog, 'target-only report catalog is missing')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'docs/reports').mkdir(parents=True)
        (self.root / 'docs/reports/README.md').write_text('# Fixed navigation\n')

    def run_dir(self, stamp, passed=None, external=False):
        directory = self.root / ('custom output' if external else 'target/verification/runs') / stamp
        directory.mkdir(parents=True)
        (directory / 'report.md').write_text('# Run\n[raw](results.json "evidence")\n')
        if passed is not None:
            (directory / 'summary.json').write_text(json.dumps({'passed': passed}))
        return directory

    def register(self, directory, suite='verification', **kwargs):
        return catalog.register_report(directory, suite, 'make report-verification', root=self.root, **kwargs)

    def test_registration_keeps_docs_and_run_bytes_unchanged(self):
        directory = self.run_dir('20261014T100000000000Z', True)
        (directory / 'SHA256SUMS').write_text('sealed evidence\n')
        before = {str(p.relative_to(self.root)): p.read_bytes()
                  for p in self.root.rglob('*') if p.is_file()}
        index = self.register(directory)
        self.assertEqual(index, self.root / 'target/reports/index.md')
        for name, data in before.items():
            self.assertEqual((self.root / name).read_bytes(), data)
        self.assertEqual(list((self.root / 'docs').rglob('*.md')), [self.root / 'docs/reports/README.md'])
        text = index.read_text()
        self.assertIn('../verification/runs/20261014T100000000000Z/report.md', text)
        self.assertIn('make report-verification', text)

    def test_latest_failed_run_keeps_earlier_latest_pass(self):
        passed = self.run_dir('20261014T100000000000Z', True)
        failed = self.run_dir('20261014T110000000000Z', False)
        self.register(failed)
        index = self.register(passed)  # registering old history later must not make it newest
        text = index.read_text()
        self.assertIn('最近运行：[20261014T110000000000Z]', text)
        self.assertIn('最近通过：[20261014T100000000000Z]', text)
        self.assertIn('FAIL', text)

    def test_empty_unknown_and_unresolved_results_cannot_be_latest_pass(self):
        for stamp, rows in [('20261014T100000000000Z', []),
                            ('20261014T110000000000Z', [{'status': 'pass'}, {'status': 'not_executed'}]),
                            ('20261014T120000000000Z', [{'status': 'cpu_failure', 'resolved_by': 'unverified'}])]:
            directory = self.run_dir(stamp)
            (directory / 'results.json').write_text(json.dumps(rows))
            index = self.register(directory)
        self.assertIn('最近通过：无', index.read_text())

    def test_raw_passing_rows_do_not_prove_a_complete_run(self):
        directory = self.run_dir('20261014T100000000000Z')
        (directory / 'results.json').write_text('[{"status":"pass"}]')
        text = self.register(directory).read_text()
        self.assertIn('历史资料 / 状态未确认', text)
        self.assertIn('最近通过：无', text)

    def test_historical_native_rows_and_recovery_links_are_not_false_failures(self):
        for stamp, rows in [('20261014T100000000000Z', [{'pass': True, 'failure': ''}]),
                            ('20261014T110000000000Z', [{'status': 'pass'},
                             {'status': 'build_platform_error', 'resolved_by': 'retry'}])]:
            directory = self.run_dir(stamp)
            (directory / 'results.json').write_text(json.dumps(rows))
            text = self.register(directory).read_text()
            self.assertNotIn('FAIL', text)
            self.assertIn('历史资料 / 状态未确认', text)
            self.assertIn('最近通过：无', text)

    def test_external_output_and_repeat_registration(self):
        directory = self.run_dir('20261014T100000000000Z', True, external=True)
        self.register(directory)
        index = self.register(directory)
        self.assertIn('../../custom%20output/', index.read_text())
        records = json.loads((self.root / 'target/reports/catalog.json').read_text())
        self.assertEqual(len(records), 1)

    def test_repeat_custom_registration_keeps_chronological_order(self):
        old = self.run_dir('old-run', True, external=True)
        new = self.run_dir('new-run', False, external=True)
        self.register(old)
        self.register(new)
        text = self.register(old).read_text()
        self.assertIn('最近运行：[new-run]', text)
        self.assertIn('最近通过：[old-run]', text)

    def test_custom_run_uses_session_time_instead_of_import_time(self):
        old = self.run_dir('old-run', True, external=True)
        new = self.run_dir('new-run', False, external=True)
        (old / 'session.json').write_text('{"started_utc":"2026-10-14T10:00:00+00:00"}')
        (new / 'session.json').write_text('{"started_utc":"2026-10-14T11:00:00+00:00"}')
        self.register(new)
        text = self.register(old).read_text()
        self.assertIn('最近运行：[new-run]', text)
        self.assertIn('最近通过：[old-run]', text)

    def test_cleanup_and_recreation_preserves_fixed_navigation(self):
        self.register(self.run_dir('20261014T100000000000Z', True))
        fixed = (self.root / 'docs/reports/README.md').read_bytes()
        shutil.rmtree(self.root / 'target')
        self.assertEqual((self.root / 'docs/reports/README.md').read_bytes(), fixed)
        index = self.register(self.run_dir('20261014T110000000000Z', False))
        self.assertIn('最近通过：无', index.read_text())
        self.assertNotIn('20261014T100000000000Z', index.read_text())

    def test_missing_report_does_not_create_catalog(self):
        with self.assertRaises(FileNotFoundError):
            self.register(self.root / 'target/no-report')
        self.assertFalse((self.root / 'target/reports').exists())

    def test_missing_old_report_is_not_listed_as_latest_pass(self):
        old = self.run_dir('20261014T100000000000Z', True)
        self.register(old)
        (old / 'report.md').unlink()
        text = self.register(self.run_dir('20261014T110000000000Z', False)).read_text()
        self.assertIn('最近通过：无', text)
        self.assertNotIn('20261014T100000000000Z', text)


class ArchiveEntrypointTests(unittest.TestCase):
    def test_archive_entrypoint_handles_custom_output_without_writing_docs(self):
        path = ROOT / 'scripts/run/verify.py'
        spec = importlib.util.spec_from_file_location('catalog_scheduler_test', path)
        verify = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(verify)
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'docs').mkdir()
            output = root / 'custom output/run'
            class FakeVerification:
                def __init__(self, directory, suite, profile):
                    self.directory = directory
                    self.env = {'VERILATOR': 'verilator', 'SBT': 'sbt'}
                    self.rows = []
                    self.collector = type('Collector', (), {'command': lambda *a, **kw: None})()
                    self.suite, self.profile = suite, profile
                def finish(self, before):
                    (self.directory / 'report.md').write_text('# Failed\n')
                    (self.directory / 'summary.json').write_text('{"passed": false}')
                    return False
            argv = ['verify.py', '--archive', '--output', str(output)]
            with patch.object(verify, 'ROOT', root), patch.object(verify, 'sources', return_value={}), \
                 patch.object(verify, 'stage_plan', return_value=[]), \
                 patch.object(verify, 'Verification', FakeVerification), patch.object(sys, 'argv', argv):
                self.assertEqual(verify.main(), 1)
            self.assertEqual(list((root / 'docs').iterdir()), [])
            self.assertTrue((root / 'target/reports/index.md').exists())
