"""Independent Chapter 9 planner, ELF layout and trace-checker requirements."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
def module(path, name):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

class Chapter9PlatformTests(unittest.TestCase):
    def setUp(self):
        self.build = module('scripts/build/build-chapter9.py', 'chapter9_build')
        self.run = module('scripts/run/test-chapter9-rtl.py', 'chapter9_run')

    def test_matrix_is_two_modes_three_latencies_three_seeds_two_resets(self):
        matrix = self.run.required_runs(['memory-switch'])
        self.assertEqual(len(matrix), 36)
        self.assertEqual(len({(r['config'], r['seed'], r['repeat']) for r in matrix}), 36)
        self.assertEqual({r['resetVector'] for r in matrix}, {0, 0x1000})

    def test_images_pad_exact_4k_and_reject_overflow(self):
        self.assertEqual(len(self.build.hex_image(b'\x13\0\0\0').splitlines()), 1024)
        with self.assertRaisesRegex(ValueError, '4 KiB'):
            self.build.hex_image(bytes(4100))
        with self.assertRaisesRegex(ValueError, 'aligned'):
            self.build.hex_image(b'x')

    def valid_trace(self):
        oracle = dict(words=[0x12345678], stores=[[0x80008000, 0x12345678, 15]],
                      readbackPC=0x100, requiredCoverage=['fetch.ram'])
        events = [dict(kind='store', cycle=1, address=0x80008000, data=0x12345678, mask=15),
                  dict(kind='commit', cycle=2, pc=0x100, instruction=0x00052503, rd=10, data=0x12345678),
                  dict(kind='store', cycle=3, address=0x8000ff20, data=0, mask=15),
                  dict(kind='store', cycle=4, address=0x8000ff24, data=0x12345678, mask=15),
                  dict(kind='store', cycle=5, address=0x8000ff28, data=1, mask=15),
                  dict(kind='store', cycle=6, address=0x8000ff00, data=0x5a, mask=15),
                  dict(kind='bus', cycle=6, event=1, address=0x80000100, data=0, readData=0x02a00593, mask=0),
                  dict(kind='end', cycle=14)]
        return oracle, events

    def test_real_cpu_lw_and_full_observation_are_required(self):
        oracle, events = self.valid_trace()
        result = self.run.check_trace(oracle, events)
        self.assertTrue(result['pass'], result)
        for name, mutate in [
            ('readback', lambda rows: rows.pop(1)),
            ('wrong-load', lambda rows: rows[1].update(data=99)),
            ('duplicate', lambda rows: rows.insert(1, copy.deepcopy(rows[0]))),
            ('window', lambda rows: rows[-1].update(cycle=13)),
            ('late-store', lambda rows: rows.insert(-1, dict(kind='store', cycle=12, address=0x80004000, data=0, mask=15))),
            ('coverage', lambda rows: rows.pop(-2)),
            ('middle-corruption', lambda rows: rows[0].update(data=0)),
        ]:
            with self.subTest(name=name):
                bad = copy.deepcopy(events)
                mutate(bad)
                self.assertFalse(self.run.check_trace(oracle, bad)['pass'])

    def test_signature_readback_order_cannot_resynchronize(self):
        oracle, events = self.valid_trace()
        events[2]['data'] = 1
        self.assertFalse(self.run.check_trace(oracle, events)['pass'])

    def test_apb_count_uses_actual_physical_read(self):
        self.assertIn('apb.count', self.run.coverage_points([
            dict(kind='bus', cycle=1, event=4, address=0x10000004, readData=3, data=0, mask=0)]))
        self.assertNotIn('apb.count', self.run.coverage_points([
            dict(kind='bus', cycle=1, event=4, address=0x10000004, readData=4, data=0, mask=0)]))

    def test_physical_writes_and_exact_traps_are_required(self):
        oracle, events = self.valid_trace()
        oracle['requirePhysical'] = True
        self.assertFalse(self.run.check_trace(oracle, events)['pass'])
        oracle['requirePhysical'] = False
        oracle['expectedTraps'] = []
        events.insert(-1, dict(kind='trap', cycle=7, cause=3, interrupt=True, pc=0x200))
        self.assertFalse(self.run.check_trace(oracle, events)['pass'])

    def test_spike_middle_error_cannot_be_hidden_by_final_signature(self):
        reference=dict(status='pass', commits=[dict(pc=0x1000,instruction=0x123,rd=1,data=3),
                                               dict(pc=0x1004,instruction=0x456,rd=2,data=4)])
        events=[dict(kind='commit',**r) for r in reference['commits']]
        self.assertTrue(self.run.compare_spike(reference,events)['passed'])
        events[0]['data']=9
        self.assertFalse(self.run.compare_spike(reference,events)['passed'])

    def test_report_rejects_shrunken_inventory_and_unchecked_passes(self):
        report = module('scripts/report/report-chapter9.py', 'chapter9_report')
        report.register_report = lambda *args, **kwargs: None
        with tempfile.TemporaryDirectory() as temp:
            import json
            directory=Path(temp)
            row=dict(name='memory-switch',config='bus-only-ram0',seed=1,repeat=0,status='pass')
            (directory/'manifest.json').write_text(json.dumps(dict(requiredRuns=[row])))
            (directory/'results.json').write_text(json.dumps([row]))
            self.assertFalse(report.report(directory))
            summary=json.loads((directory/'summary.json').read_text())
            self.assertFalse(summary['fullMatrix'])
            self.assertFalse(summary['selectedPassed'])

    def test_pc_link_normalization_does_not_accept_wrong_raw_link(self):
        reference=dict(status='pass',commits=[dict(pc=0x1000,instruction=0x000280e7,rd=1,data=7)])
        events=[dict(kind='commit',**reference['commits'][0])]
        self.assertFalse(self.run.compare_spike(reference,events)['passed'])

    def test_raw_subword_source_and_physical_active_lanes_are_separate(self):
        oracle,events=self.valid_trace()
        oracle.update(requirePhysical=True,physicalStores=[
            dict(address=0x80004001,mask=2,bytes=[None,0xaa,None,None]),
            dict(address=0x80008000,mask=15,bytes=[0x78,0x56,0x34,0x12])])
        oracle['stores'].insert(0,[0x80004001,0xaa,2])
        events.insert(0,dict(kind='store',cycle=0,address=0x80004001,data=0xaa,mask=2))
        bus=[]
        for event in events:
            if event['kind']=='store':
                data=0x11aaAA11 if event['mask']==2 else event['data']
                bus.append(dict(kind='bus',cycle=event['cycle'],event=3,address=event['address'],data=data,mask=event['mask']))
        events=sorted(events+bus,key=lambda e:e['cycle'])
        self.assertTrue(self.run.check_trace(oracle,events)['pass'])
        physical=next(e for e in events if e['kind']=='bus' and e['event']==3)
        physical['data']^=1 # inactive lane must not be compared
        self.assertTrue(self.run.check_trace(oracle,events)['pass'])
        physical['data']^=0x100 # active byte must be compared
        self.assertFalse(self.run.check_trace(oracle,events)['pass'])

    def test_big_endian_store_has_independent_physical_bytes(self):
        physical=self.build.physical_stores('privilege-endian',False)
        word=next(e for e in physical if e['address']==0x80004004)
        self.assertEqual(word['bytes'],[0xaa,0xbb,0xcc,0xdd])
        self.assertIn([0x80004004,0xaabbccdd,15],self.build.expected_stores('privilege-endian',False))

    def test_torn_trace_preserves_completed_prefix(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'trace.jsonl'
            path.write_text('{"kind":"end","repeat":0,"cycle":10}\n{"kind":')
            rows,error=self.run.read_trace(path)
            self.assertEqual(rows,[dict(kind='end',repeat=0,cycle=10)])
            self.assertIn('line 2',error)

    def test_interrupt_causality_requires_rise_acceptance_then_edge_fall(self):
        oracle,events=self.valid_trace()
        oracle['expectedTraps']=[dict(cause=3,interrupt=True,pc=0x120)]
        rise=dict(kind='irq',cycle=0,asserted=True,committed_m=1,committed_f=0)
        trap=dict(kind='trap',cycle=1,cause=3,interrupt=True,pc=0x120,committed_m=0,committed_f=0,mswint=True)
        fall=dict(kind='irq',cycle=1,asserted=False,afterAcceptedTrapEdge=True,committed_m=0,committed_f=0)
        events=sorted([rise,trap,fall]+events,key=lambda e:e['cycle'])
        self.assertTrue(self.run.check_trace(oracle,events)['pass'])
        variants={}
        bad=copy.deepcopy(events); bad.remove(next(e for e in bad if e['kind']=='irq' and not e['asserted']));variants['missing-fall']=bad
        bad=copy.deepcopy(events); bad.remove(next(e for e in bad if e['kind']=='irq' and e['asserted']));variants['missing-rise']=bad
        bad=copy.deepcopy(events); next(e for e in bad if e['kind']=='irq' and e['asserted'])['cycle']=2;variants['rise-after-trap']=sorted(bad,key=lambda e:e['cycle'])
        bad=copy.deepcopy(events); next(e for e in bad if e['kind']=='irq' and not e['asserted'])['cycle']=0;variants['early-fall']=sorted(bad,key=lambda e:e['cycle'])
        bad=copy.deepcopy(events); next(e for e in bad if e['kind']=='irq' and not e['asserted'])['afterAcceptedTrapEdge']=False;variants['fall-before-edge']=bad
        bad=copy.deepcopy(events); next(e for e in bad if e['kind']=='trap')['mswint']=False;variants['trap-while-low']=bad
        bad=copy.deepcopy(events); next(e for e in bad if e['kind']=='trap')['committed_m']=1;variants['trap-while-committed']=bad
        for name,bad in variants.items():
            with self.subTest(name=name): self.assertFalse(self.run.check_trace(oracle,bad)['pass'])

    def test_latency_coverage_has_zero_and_positive_contracts(self):
        base=dict(requiredCoverage=['fetch.ram','bus.wait'])
        zero=self.run.configured_oracle(base,0)
        self.assertEqual(zero['requiredCoverage'],['fetch.ram','bus.zero-wait'])
        two=self.run.configured_oracle(base,2)
        self.assertIn('bus.wait.length2',two['requiredCoverage'])
        self.assertEqual(base['requiredCoverage'],['fetch.ram','bus.wait'])
        self.assertIn('bus.zero-wait',self.run.coverage_points([dict(kind='bus',cycle=3,event=2)]))
        self.assertIn('bus.wait.length2',self.run.coverage_points([
            dict(kind='sample',cycle=3,bus_wait=1,ifu_wait=1,lsu_wait=0),
            dict(kind='sample',cycle=4,bus_wait=1,ifu_wait=1,lsu_wait=0)]))

    def test_expected_ram_and_apb_results_are_explicit(self):
        self.assertEqual(self.build.oracle_words('apb-subword', False),
                         [0x11223344, 0x1122aa44, 0xbbccaa44, 3])
        self.assertIn(0xffffff80, self.build.oracle_words('memory-switch', False))
        self.assertEqual(self.build.oracle_words('memory-switch', True)[-1], 0x55aa33cc)

if __name__ == '__main__':
    unittest.main()
