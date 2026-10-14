"""The report must reject evidence that does not match independent signatures."""
import importlib.util
from pathlib import Path
import unittest
import os
import tempfile
import time

PATH = Path(__file__).resolve().parents[3] / 'scripts/report-chapter8.py'

class Chapter8ReportEvidenceTests(unittest.TestCase):
    def test_fail_closed_on_incomplete_or_changed_evidence(self):
        self.assertTrue(PATH.exists(), 'chapter8 report evidence validator is missing')
        spec = importlib.util.spec_from_file_location('chapter8_report', PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        expected = [{'address': '512', 'data': '90', 'mask': '15'}]
        def run(**kw):
            value = {'repeat': 0, 'cycles': 10, 'pass': True, 'failure': '',
                     'actual': [{'cycle': 1, **expected[0]}]}
            value.update(kw)
            return value
        module.validate_run(expected, run())
        for bad in [run(actual=[]), run(**{'pass': False}), run(cycles=2),
                    run(actual=[{'cycle': 1, 'address': '512', 'data': '91', 'mask': '15'}]),
                    run(cycles=10001, actual=[{'cycle': 9992, **expected[0]}]),
                    run(cycles=0, actual=[{'cycle': -9, **expected[0]}])]:
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                module.validate_run(expected, bad)

    def test_reject_stale_sources_and_incomplete_junit(self):
        spec = importlib.util.spec_from_file_location('chapter8_report', PATH)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'result.json'
            path.write_text('{}')
            started = time.time() - 1
            module.validate_freshness(started, {'a': 'sha'}, {'a': 'sha'}, [path])
            os.utime(path, (started - 1, started - 1))
            with self.assertRaises(ValueError):
                module.validate_freshness(started, {'a': 'sha'}, {'a': 'sha'}, [path])
            with self.assertRaises(ValueError):
                module.validate_freshness(started, {'a': 'old'}, {'a': 'new'}, [])
            xml = Path(name) / 'TEST-a.xml'
            xml.write_text('<testsuite name="a" tests="1"><testcase name="one"/></testsuite>')
            module.validate_junit([xml], {'a'}, 1)
            for paths, count in [([], 1), ([xml], 2)]:
                with self.assertRaises(ValueError):
                    module.validate_junit(paths, {'a'}, count)
            xml.write_text('<testsuite name="a" tests="1"><testcase name="one"><skipped/></testcase></testsuite>')
            with self.assertRaises(ValueError):
                module.validate_junit([xml], {'a'}, 1)
