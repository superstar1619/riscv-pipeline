"""Fail-closed evidence checks and continue-after-failure behavior."""
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class CollectionTests(unittest.TestCase):
    def module(self):
        path = ROOT / 'scripts/lib/cvw_common.py'
        self.assertTrue(path.exists(), 'missing evidence collector')
        spec = importlib.util.spec_from_file_location('cvw_common', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_failure_is_recorded_and_next_command_runs(self):
        module = self.module()
        with tempfile.TemporaryDirectory() as directory:
            collector = module.Collector(Path(directory))
            self.assertEqual(collector.command('bad', [sys.executable, '-c', 'exit(3)'])['exit'], 3)
            self.assertEqual(collector.command('good', [sys.executable, '-c', 'print(42)'])['exit'], 0)
            self.assertEqual(len(collector.commands), 2)
            self.assertIn('42', (Path(directory) / 'good.log').read_text())

    def test_readback_rejects_mutations(self):
        module = self.module()
        expected = [1, 2, 0xdeadbeef]
        good = {'words': expected, 'length': 8, 'guard': 0xcafef00d,
                'canary': 0, 'done': True, 'postCycles': 8, 'postStores': 0}
        module.validate_readback(expected, 8, good)
        for mutation in [dict(words=[0, 2, 0xdeadbeef]), dict(words=expected[:-1]),
                         dict(words=expected + [0]), dict(length=12), dict(done=False),
                         dict(postCycles=7), dict(postStores=1), dict(guard=0)]:
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                module.validate_readback(expected, 8, {**good, **mutation})

    def test_no_pass_for_missing_or_incomplete_reset_runs(self):
        module = self.module()
        for runs in [[], [{'pass': True}], [{'pass': True}, {'pass': False}]]:
            self.assertFalse(module.two_runs_pass({'runs': runs}))
        self.assertTrue(module.two_runs_pass({'runs': [{'repeat': 0, 'pass': True},
                                                       {'repeat': 1, 'pass': True}]}))


if __name__ == '__main__':
    unittest.main()
