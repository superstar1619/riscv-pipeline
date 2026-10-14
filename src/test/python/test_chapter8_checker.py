"""Exercise the actual independent RTL checker with deliberately bad expectations."""
import json
import os
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE_ART = ROOT / 'target/chapter8-testplan'
POINTER = BASE_ART / 'current-run.txt'
ART = (Path(os.environ['CHAPTER8_RUN_DIR']) if os.environ.get('CHAPTER8_RUN_DIR') else (ROOT / POINTER.read_text().strip() if POINTER.exists() else BASE_ART)).resolve()

class Chapter8CheckerNegativeTests(unittest.TestCase):
    def test_reject_signature_missing_extra_and_timeout(self):
        binary = ART / 'rtl/default/VChapter8'
        self.assertTrue(binary.exists(), 'independent chapter8 RTL checker has not been built')
        case = 'default-CS01'
        source = ART / f'cases/{case}.events'
        image = ART / f'cases/{case}.hex'
        (ART / 'rtl/default/program.hex').write_bytes(image.read_bytes())
        rows = source.read_text().splitlines()
        bad = rows.copy()
        words = bad[0].split()
        words[1] = f'{int(words[1], 16) ^ 1:08x}'
        bad[0] = ' '.join(words)
        variants = {'signature': (bad, 10000),
                    'missing': (rows + ['00000100 00000000 0000000f'], 10000),
                    'extra': (rows[:-1], 10000), 'timeout': (rows, 1)}
        valid = json.loads((ART / f'rtl-results/{case}-s1.json').read_text())
        done_cycle = valid['runs'][0]['actual'][-1]['cycle']
        variants['observation-window'] = (rows, done_cycle + 1)
        for name, (events, limit) in variants.items():
            with self.subTest(name=name):
                directory = ART / 'negative'
                directory.mkdir(exist_ok=True)
                expected = directory / f'{name}.events'
                result = directory / f'{name}.json'
                expected.write_text('\n'.join(events) + '\n')
                command = [str(binary), str(expected), '1', str(limit), '0', '0', '0', '0', '0', str(result)]
                run = subprocess.run(command, cwd=ART / 'rtl/default', capture_output=True, text=True)
                (directory / f'{name}.log').write_text(run.stdout + run.stderr)
                self.assertEqual(run.returncode, 1, run.stdout + run.stderr)
                evidence = json.loads(result.read_text())
                self.assertFalse(evidence['pass'])
                self.assertIn({'signature': 'mismatch', 'missing': 'missing',
                               'extra': 'extra', 'timeout': 'timeout', 'observation-window': 'observation'}[name], evidence['failure'])
