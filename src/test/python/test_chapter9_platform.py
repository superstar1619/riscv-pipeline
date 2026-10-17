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

    def test_expected_ram_and_apb_results_are_explicit(self):
        self.assertEqual(self.build.oracle_words('apb-subword', False),
                         [0x11223344, 0x1122aa44, 0xbbccaa44, 3])
        self.assertIn(0xffffff80, self.build.oracle_words('memory-switch', False))
        self.assertEqual(self.build.oracle_words('memory-switch', True)[-1], 0x55aa33cc)

if __name__ == '__main__':
    unittest.main()
