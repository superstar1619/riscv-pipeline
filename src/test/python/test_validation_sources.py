"""Regression checks for silently reduced upstream architecture coverage."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('builder', ROOT / 'scripts/build-validation.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


class SourceInventorySpec(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        repo = Path(self.temp.name) / 'upstream'
        repo.mkdir()
        self.repo = repo
        self.src = repo / 'riscv-test-suite/rv32i_m/I/src'
        self.src.mkdir(parents=True)
        for name in ('add-01', 'xor-01'):
            (self.src / f'{name}.S').write_text('inst_0:\n')
        extra = repo / 'riscv-test-suite/rv32i_m/privilege/src'
        extra.mkdir(parents=True)
        (extra / 'misalign1-jalr-01.S').write_text('inst_0:\n')
        self.env = repo / 'riscv-test-suite/env'
        self.env.mkdir(parents=True)
        (self.env / 'arch_test.h').write_text('.option rvc\n')
        (self.env / 'encoding.h').write_text('fixture\n')
        subprocess.check_call(['git', 'init', '-q', str(repo)])
        subprocess.check_call(['git', '-C', str(repo), 'add', '.'])
        subprocess.check_call(['git', '-C', str(repo), '-c', 'user.name=Validation',
                               '-c', 'user.email=validation@localhost', 'commit', '-qm', 'fixture'])
        revision = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
        for name, value in [('ARCH', repo), ('ARCH_REVISION', revision),
                            ('BUILD', Path(self.temp.name) / 'build')]:
            p = patch.object(builder, name, value)
            p.start()
            self.addCleanup(p.stop)
        for name in ('compile_file', 'build_image'):
            p = patch.object(builder, name, return_value={})
            p.start()
            self.addCleanup(p.stop)

    def test_deleted_test_is_rejected(self):
        (self.src / 'xor-01.S').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing upstream source'):
            builder.arch_tests(None)

    def test_mixed_valid_and_unknown_selection_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Unknown architecture tests'):
            builder.arch_tests(['add-01', 'typo-01'])

    def test_deleted_environment_header_is_rejected(self):
        builder.arch_tests(None)  # Leave a copied header from a prior successful build.
        (self.env / 'encoding.h').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing upstream source'):
            builder.arch_tests(None)

    def test_complete_inventory_and_textbook_extra_are_selected(self):
        self.assertEqual(len(builder.arch_tests(None)), 3)
        self.assertEqual(len(builder.arch_tests(['misalign1-jalr-01'])), 1)


if __name__ == '__main__':
    unittest.main()
