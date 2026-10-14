"""Review regression: torn evidence must not abort a matrix; replay uses fresh paths."""
from pathlib import Path
import sys
import tempfile
import unittest
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'scripts'))
import cvw_common as common

class ReviewFixTests(unittest.TestCase):
    def test_torn_and_missing_evidence_are_classified(self):
        self.assertTrue(hasattr(common, 'read_evidence'), 'torn evidence handling missing')
        with tempfile.TemporaryDirectory() as name:
            p=Path(name)/'result.json'
            for value in (None, '{"runs":[', '[]', '{"runs":[null]}'):
                if value is not None: p.write_text(value)
                self.assertIn('evidenceError', common.read_evidence(p))
            p.write_text('{"runs":[]}')
            self.assertEqual(common.read_evidence(p), {'runs':[]})

    def test_reconcile_backfills_and_rejects_duplicates(self):
        self.assertTrue(hasattr(common, 'reconcile'), 'matrix reconciliation missing')
        expected=[dict(chapter=8,suite='cvw',name='a',config='default',seed=1,repeat=i) for i in (0,1)]
        partial=[dict(expected[0],status='pass')]
        result=common.reconcile(partial,expected,'shared failure')
        self.assertEqual([r['status'] for r in result],['pass','not_executed'])
        self.assertEqual(common.reconcile(partial*2,expected,'x')[0]['status'],'build_platform_error')

    def test_reproduction_uses_fresh_launcher_and_explicit_tools(self):
        self.assertTrue(hasattr(common, 'fresh_reproduction'), 'safe replay launcher missing')
        command=common.fresh_reproduction('existing-rtl', {'SBT':'./scripts/sbt-local.sh','CVW_ROOT':'/tmp/cvw with spaces'})
        self.assertIn('reproduce-chapter7-8.py',command)
        self.assertIn('existing-rtl',command)
        self.assertIn('CVW_ROOT=',command)
        self.assertNotIn('current-run.txt',command)
        self.assertNotIn('seed-1.json',command)

if __name__=='__main__':unittest.main()
