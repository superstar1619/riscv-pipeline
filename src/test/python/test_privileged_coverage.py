"""Acceptance and rejection tests for event-backed privileged functional bins."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('privileged_coverage', ROOT / 'scripts/privileged_coverage.py')
coverage = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage)


def evidence(bin_id):
    proof = dict(cycle=12, pc='256', instruction=str(0x13), validM=True,
        retired=True, trap=False, interrupt=False, cause='0', mode=3, targetMode=3,
        pending='0', previousPending='0', status='0', globalEnable=0, wfiStall=False, loadStall=False,
        endian='little', address='128', memRW='0', funct3=0, previousRetiredWasWfi=False, loadInE=False)
    if bin_id.startswith('csr.'):
        op = int(bin_id.split('.')[1][2:])
        proof['instruction'] = str((0x340 << 20) | (op << 12) | 0x73)
    elif bin_id.startswith('trap.'):
        proof.update(retired=False, trap=True, cause='2', instruction=str(0xffffffff))
    elif bin_id.startswith('memory.'):
        proof.update(instruction=str(0x00202023), memRW='1', funct3=2)
    elif bin_id.startswith('instruction.'):
        opcode = {'alu': 0x13, 'lui': 0x37, 'store': 0x23, 'csr': 0x1073,
                  'system': 0x10500073, 'jal': 0x6f}[bin_id.split('.')[1]]
        proof['instruction'] = str(opcode)
    elif bin_id.startswith('wfi.stall.'):
        proof.update(instruction=str(0x10500073), retired=False, wfiStall=True)
    elif bin_id.startswith('wfi.wake.'):
        proof.update(instruction=str(0x10500073), pending='128')
    elif bin_id == 'interrupt.arrival.wfi':
        proof.update(instruction=str(0x10500073), pending='128')
    elif bin_id.startswith('interrupt.pending1.'):
        proof['pending'] = '128'
    return dict(count=1, first=proof)


class PrivilegedCoverageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        (self.directory / 'scala').mkdir()
        cases = [dict(id=case_id, config='default', key='default-' + case_id)
                 for case_id in sorted(coverage.SMOKE_IDS)]
        (self.directory / 'cases.json').write_text(json.dumps(cases))
        self.bins = {key: evidence(key) for key in coverage.required_bins('default', 'smoke')}
        for case in cases:
            for repeat in (0, 1):
                self.write(case['key'], repeat, self.bins)

    def write(self, key, repeat, bins, passed=True):
        (self.directory / 'scala' / f'{key}-r{repeat}.json').write_text(json.dumps(
            dict(key=key, repeat=repeat, seed=1, **{'pass': passed}, functionalCoverage=bins)))

    def test_actual_event_evidence_satisfies_smoke(self):
        report = coverage.aggregate(self.directory, 'smoke')
        self.assertTrue(report['passed'], report)
        self.assertEqual(report['passingRuns'], 8)
        self.assertEqual(report['configurations']['default']['bins']['csr.op1']['count'], 8)

    def test_failed_runs_are_retained_but_cannot_satisfy_bins(self):
        for path in (self.directory / 'scala').glob('*.json'):
            data = json.loads(path.read_text())
            data['pass'] = False
            path.write_text(json.dumps(data))
        report = coverage.aggregate(self.directory, 'smoke')
        self.assertFalse(report['passed'])
        self.assertEqual(report['passingRuns'], 0)
        self.assertEqual(len(report['failedRuns']), 8)
        self.assertIn('csr.op1', report['configurations']['default']['missing'])

    def test_missing_reset_evidence_fails_despite_all_bins_elsewhere(self):
        (self.directory / 'scala/default-CS01-r1.json').unlink()
        report = coverage.aggregate(self.directory, 'smoke')
        self.assertFalse(report['passed'])
        self.assertEqual(report['failures'][0]['case'], 'default-CS01')
        self.assertEqual(report['configurations']['default']['missing'], [])

    def test_instruction_presence_without_retirement_cannot_count(self):
        bins = copy.deepcopy(self.bins)
        bins['csr.op1']['first']['retired'] = False
        self.write('default-CS01', 0, bins)
        report = coverage.aggregate(self.directory, 'smoke')
        self.assertFalse(report['passed'])
        self.assertIn('no architectural event', report['failures'][0]['reason'])

    def test_missing_bin_and_mismatched_trap_evidence_fail(self):
        for path in (self.directory / 'scala').glob('*.json'):
            data = json.loads(path.read_text())
            del data['functionalCoverage']['csr.op7']
            path.write_text(json.dumps(data))
        report = coverage.aggregate(self.directory, 'smoke')
        self.assertFalse(report['passed'])
        self.assertIn('csr.op7', report['configurations']['default']['missing'])
        invalid = evidence('trap.cause2.from3.to3.interrupt0')
        invalid['first']['mode'] = 0
        with self.assertRaisesRegex(ValueError, 'trap evidence mismatch'):
            coverage.validate_bin('trap.cause2.from3.to3.interrupt0', invalid)

    def test_supported_configurations_have_explicit_boundaries(self):
        minimal = coverage.required_bins('minimal', 'full')
        normal = coverage.required_bins('default', 'full')
        self.assertNotIn('privilege.mode1', minimal)
        self.assertIn('privilege.mode1', normal)
        self.assertNotIn('memory.big.load.width1.offset3', minimal)
        self.assertIn('memory.big.load.width1.offset3', normal)
        self.assertIn('memory.little.store.width2.offset2', minimal)

    def test_counter_without_first_cycle_is_not_evidence(self):
        with self.assertRaisesRegex(ValueError, 'actual cycle evidence'):
            coverage.validate_bin('csr.op1', dict(count=17))

    def test_relabelled_instruction_mode_memory_and_pending_are_rejected(self):
        examples = [('instruction.load', evidence('instruction.alu')),
                    ('privilege.mode1', evidence('privilege.mode3')),
                    ('memory.mode1.big', evidence('memory.little.store.width4.offset0')),
                    ('wfi.stall.mode1.pending0', evidence('wfi.stall.mode3.pending0')),
                    ('wfi.wake.mode1.pending1', evidence('wfi.wake.mode3.pending1')),
                    ('csr.op1.invented', evidence('csr.op1'))]
        stalled = evidence('privilege.mode3')
        stalled['first'].update(retired=False, loadStall=True, loadInE=True)
        examples.append(('stall.load_use.pending1', stalled))
        for label, proof in examples:
            with self.subTest(label=label), self.assertRaisesRegex(ValueError, 'label does not match'):
                coverage.validate_bin(label, proof)

    def test_invalid_slot_simultaneous_trap_and_forged_global_bit_are_rejected(self):
        for mutation, message in ((dict(validM=False), 'invalid M instruction'),
                                  (dict(trap=True), 'mutually exclusive'),
                                  (dict(globalEnable=1), 'actual status/mode')):
            proof = evidence('privilege.mode3')
            proof['first'].update(mutation)
            with self.subTest(mutation=mutation), self.assertRaisesRegex(ValueError, message):
                coverage.validate_bin('privilege.mode3', proof)

    def test_wrong_wfi_interrupt_mode_is_rejected(self):
        proof = evidence('trap.cause2.from3.to3.interrupt0')
        proof['first'].update(interrupt=True, previousRetiredWasWfi=True)
        with self.assertRaisesRegex(ValueError, 'label does not match'):
            coverage.validate_bin('interrupt.after_wfi.mode1', proof)

    def test_csr_late_result_stall_cannot_satisfy_load_use(self):
        proof = evidence('csr.op1')
        proof['first'].update(retired=False, loadStall=True, loadInE=False)
        with self.assertRaisesRegex(ValueError, 'without actual hazard'):
            coverage.validate_bin('stall.load_use.pending0', proof)

    def test_deleted_manifest_entry_cannot_shrink_required_full_inventory(self):
        inventory = json.loads(coverage.INVENTORY.read_text())['configurations']
        cases = [dict(id=case, config=config, key=config + '-' + case)
                 for config, ids in inventory.items() for case in ids]
        cases = [case for case in cases if case['key'] != 'default-EX02']
        (self.directory / 'cases.json').write_text(json.dumps(cases))
        report = coverage.aggregate(self.directory, 'full')
        self.assertFalse(report['passed'])
        self.assertIn('required fixture inventory differs', report['failures'][0]['reason'])
        self.assertIn('EX02', report['failures'][0]['reason'])

    def test_already_pending_interrupt_is_not_an_arrival(self):
        proof = evidence('interrupt.arrival.wfi')
        proof['first']['previousPending'] = '128'
        with self.assertRaisesRegex(ValueError, 'no actual pending arrival'):
            coverage.validate_bin('interrupt.arrival.wfi', proof)


if __name__ == '__main__':
    unittest.main()
