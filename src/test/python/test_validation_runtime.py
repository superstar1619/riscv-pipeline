"""Replay each image from its own directory, including external build roots."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ValidationRuntimeTests(unittest.TestCase):
    def test_derived_rom_path_leaves_production_source_unchanged(self):
        prepare = load('validation_rtl_fixture', 'prepare-validation-rtl.py').prepare
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, derived = root/'production.v', root/'build/validation.v'
            original = 'initial begin\n  $readmemh("target/old/program.memfile", ROM);\nend\n'
            source.write_text(original)
            prepare(source, derived)
            self.assertEqual(source.read_text(), original)
            self.assertIn('$readmemh("program.memfile", ROM)', derived.read_text())
            with self.assertRaisesRegex(ValueError, 'must not replace'):
                prepare(source, source)
            source.write_text(original + original)
            with self.assertRaisesRegex(ValueError, 'exactly one'):
                prepare(source, derived)

    def test_external_artifact_paths_remain_resolvable(self):
        builder = load('validation_builder_paths', 'build-validation.py')
        with tempfile.TemporaryDirectory() as directory:
            external = Path(directory)/'program.elf'
            external.write_bytes(b'fixture')
            self.assertEqual(ROOT / builder.artifact_path(external), external)
        local = ROOT/'target/case/program.elf'
        self.assertEqual(builder.artifact_path(local), 'target/case/program.elf')

    def test_completed_suite_can_replay_first_case_without_overwriting_any_image(self):
        runner = load('validation_runner_fixture', 'run-validation.py')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / 'rtl/VRiscvPipeline'
            executable.parent.mkdir()
            executable.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
reference, base, seed, limit, label = sys.argv[1:]
if int(limit) == 1:
    print('Cycle limit reached'); sys.exit(1)
image = pathlib.Path('program.memfile')
if not image.exists():
    print('missing private image'); sys.exit(1)
actual = image.read_text().strip()
if actual != pathlib.Path(reference).read_text().strip():
    print('Signature word 0 differs'); sys.exit(1)
if pathlib.Path.cwd().name != label:
    print('incorrect case directory'); sys.exit(1)
if 'SIGNATURE_OUTPUT' in os.environ:
    pathlib.Path(os.environ['SIGNATURE_OUTPUT']).write_text(actual+'\\n')
    pathlib.Path(os.environ['SIGNATURE_OUTPUT']+'.cpu').write_text(actual+'\\n')
print(f'PASS {label} seed={seed} cycles=20 signature_words=1')
''')
            executable.chmod(0o755)
            programs = []
            for name, value in [('first', '12345678'), ('second', '87654321')]:
                case = root / name
                case.mkdir()
                (case / 'program.memfile').write_text(value + '\n')
                (case / 'reference.signature').write_text(value + '\n')
                programs.append(dict(name=name, image=str(case/'program.memfile'),
                                     reference=str(case/'reference.signature'), sigbase='80210000',
                                     cycleLimit=100, signatureWords=1))
            (root/'arch.json').write_text(json.dumps(dict(programs=programs)))
            with patch.object(runner, 'BUILD', root), patch.object(sys, 'argv', ['run-validation', 'arch']), \
                    patch.dict(os.environ, {'VALIDATION_SEEDS': '1'}), contextlib.redirect_stdout(io.StringIO()):
                code = runner.main()
            self.assertEqual(code, 0, (root/'arch-results.json').read_text())
            self.assertFalse((root/'program.memfile').exists(), 'shared mutable image must not be created')
            data = json.loads((root/'arch-results.json').read_text())
            replay = subprocess.run(data['results'][0]['reproduce'], shell=True, text=True, capture_output=True)
            self.assertEqual(replay.returncode, 0, replay.stdout + replay.stderr)
            self.assertIn('PASS first ', replay.stdout)
            commands = [json.loads(line) for line in (root/'commands.jsonl').read_text().splitlines()]
            self.assertTrue(all(Path(r['cwd']).name in ('first', 'second') for r in commands))


if __name__ == '__main__':
    unittest.main()
