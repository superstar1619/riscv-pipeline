"""Exercise real C++ protocol rejection behavior without a Verilator build."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class SignatureProtocolSpec(unittest.TestCase):
    def test_cpu_readback_and_protocol_faults(self):
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'signature-protocol-test'
            subprocess.run(['c++', '-std=c++17', '-Wall', '-Wextra', '-Werror',
                            str(ROOT / 'src/test/cpp/SignatureProtocolTest.cpp'),
                            '-o', str(executable)], check=True, capture_output=True, text=True)
            result = subprocess.run([str(executable)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('PASS signature protocol', result.stdout)


if __name__ == '__main__':
    unittest.main()
