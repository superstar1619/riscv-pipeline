import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/lib'))


class AdapterTests(unittest.TestCase):
    def module(self):
        path = ROOT / 'scripts/build/build-chapter8-cvw.py'
        self.assertTrue(path.exists(), 'missing CVW adapter')
        spec = importlib.util.spec_from_file_location('cvw_adapter', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_complete_textbook_inventory(self):
        module = self.module()
        self.assertEqual(len(module.APPLICABLE), 18)
        self.assertEqual(len(module.EXCLUDED), 10)
        self.assertEqual(len(set(module.APPLICABLE) | set(module.EXCLUDED)), 28)

    def test_independent_missing_reference_expectations(self):
        module = self.module()
        self.assertEqual(module.independent('minfo', '', {}), [2, 17] * 5 + [11])
        self.assertEqual(module.independent('misa', '', {}), [0x111, 11])
        self.assertEqual(module.independent('csr-permission-u', 'WRITE_READ_CSR satp, 1\n', {}),
                         [11, 2, 2, 0xbad, 8])

    def test_async_handlers_resume_epc_and_sstc_starts_disabled(self):
        module = self.module()
        source = (module.CVW / module.REL / 'src/WALLY-TEST-LIB-32.h').read_text()
        adapted = module.adapt_library(source, True)
        self.assertIn('csrw stimecmp, t0', adapted)
        for name in ('soft_interrupt_', 'time_interrupt_', 'ext_interrupt_'):
            section = adapted[adapted.index('\n' + name + r'\MODE\():'):]
            section = section[:section.index('j trapreturn_finished_')]
            self.assertIn(r'csrr ra, \MODE\()epc', section)
        self.assertIn('cvw_finish', adapted)

    def test_reference_relocation_is_semantic(self):
        module = self.module()
        text = ('00000001 # mcause instruction access fault\n'
                '00000000 # mtval faulting instruction address\n'
                '80000168 # mtval breakpoint instruction address\n'
                '80000171 # mtval misaligned address\n'
                '0000b3fe # medeleg readback\n')
        values = module.relocate_reference(text, {'cause_breakpnt': 0x100,
                  'cause_load_addr_misaligned': 0x120, 'cause_store_addr_misaligned': 0x140})
        self.assertEqual(values, [1, 0x10000, 0x100, 0x121, 0x3ff])


if __name__ == '__main__':
    unittest.main()
