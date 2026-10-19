"""Fail-closed CLI limits: budget input must be explicit, tied to file digest."""
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class TestAuditCli(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        source = Path(self.tmp.name, 'Example.java')
        source.write_text('public class Example { '
                          'private static int inc(int x) { return x + 1; } '
                          'public static int tick(int x) { return inc(x); } }')
        subprocess.run(['javac', '--release', '8', '-g:none', '-d', self.tmp.name, str(source)],
                       capture_output=True, check=True)
        self.classfile = Path(self.tmp.name, 'Example.class')

    def run_audit(self, *extra):
        call = subprocess.run(['python3', str(ROOT / 'tools/rt_bytecode_audit.py'),
                               str(self.classfile), '--entry', 'tick', *extra],
                              capture_output=True, text=True)
        return call.returncode, json.loads(call.stdout)

    def test_digest_and_contract_identifier(self):
        code, report = self.run_audit('--max-bytecodes', '200', '--max-call-depth', '2')
        self.assertEqual(code, 0, report)
        self.assertEqual(report['contract_id'], 'oreslang-jvm-primitive-dag-v1')
        self.assertEqual(report['input_sha256'], hashlib.sha256(self.classfile.read_bytes()).hexdigest())

    def test_instruction_limit_is_enforced(self):
        code, report = self.run_audit('--max-bytecodes', '2')
        self.assertEqual(code, 1)
        self.assertIn('budget', report['reason'])

    def test_call_depth_limit_is_enforced(self):
        code, report = self.run_audit('--max-call-depth', '1')
        self.assertEqual(code, 1)
        self.assertIn('call depth', report['reason'])

    def test_malformed_budgets_rejected(self):
        for args in (('--max-bytecodes', '-1'), ('--max-call-depth', '0')):
            with self.subTest(args=args):
                code, report = self.run_audit(*args)
                self.assertEqual(code, 1)
                self.assertEqual(report['status'], 'REJECT')


if __name__ == '__main__':
    unittest.main()
