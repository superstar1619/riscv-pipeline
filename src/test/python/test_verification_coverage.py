import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'scripts/lib'))
from verification_coverage import collect

class CoverageTests(unittest.TestCase):
    def test_actual_forwarding_only(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)
            (path/'commits').write_text(json.dumps(dict(order=0,cycle=4,pc=0,instruction=0x00100093,rd=1,value=1,mem=None))+'\n')
            events=[dict(cycle=1,advance=0,instruction=0x00100093,forwardA=2,forwardB=0,loadStall=0,storeStall=0,redirect=0),dict(cycle=2,advance=1,instruction=0x00108093,forwardA=1,forwardB=0,loadStall=1,storeStall=0,redirect=0)]
            (path/'events').write_text(''.join(json.dumps(e)+'\n' for e in events))
            cov=collect(path/'commits',path/'events')
            self.assertNotIn('forward.alu.A.M',cov)
            self.assertEqual(cov['forward.alu.A.W']['count'],1)
            self.assertEqual(cov['instruction.alu']['first']['pc'],0)
            self.assertEqual(cov['stall.load_use']['count'],1)

    def test_branch_to_next_pc_uses_actual_decision(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)
            row=dict(order=0,cycle=4,pc=0,instruction=0x00000263,rd=0,value=0,mem=None,branchTaken=1)
            (path/'commits').write_text(json.dumps(row)+'\n')
            (path/'events').write_text('')
            cov=collect(path/'commits',path/'events')
            self.assertIn('branch.taken',cov)
            self.assertNotIn('branch.not_taken',cov)
