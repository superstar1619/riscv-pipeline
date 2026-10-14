from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'scripts/lib'))
from cvw_common import rebase_report_links


class PublicationEdges(unittest.TestCase):
    def test_url_named_label_is_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = root/'target/run'
            run.mkdir(parents=True)
            (run/'report.md').write_text('[results.json](results.json "raw evidence")\n')
            (run/'results.json').write_text('{}')
            text = rebase_report_links((run/'report.md').read_text(), run/'report.md',
                                       root/'docs/run/report.md', {})
            self.assertIn('[results.json](../../target/run/results.json "raw evidence")', text)

    def test_direct_entrypoint_propagates_cache_to_children(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            shutil.copytree(ROOT/'scripts', root/'scripts', ignore=shutil.ignore_patterns('__pycache__'))
            env = dict(os.environ)
            env.pop('PYTHONPYCACHEPREFIX', None)
            env.pop('PYTHONDONTWRITEBYTECODE', None)
            code = ('import runpy, subprocess, sys; '
                    'runpy.run_path(sys.argv[1], run_name="entrypoint_contract"); '
                    'subprocess.run([sys.executable, "-c", "import sys; print(sys.pycache_prefix)"], check=True)')
            result = subprocess.run([sys.executable, '-c', code, str(root/'scripts/run/verify.py')],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), str(root/'target/cache/python'))


if __name__ == '__main__':
    unittest.main()
