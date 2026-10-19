import subprocess
import tempfile
import unittest
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from rt_bytecode_audit import AuditError, audit


class TestBytecodeAdmission(unittest.TestCase):
    def compile(self, source):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'Example.java'
            path.write_text('public class Example {\n' + source + '\n}\n')
            subprocess.run(['javac', '--release', '8', '-g:none', '-d', tmp, str(path)], check=True, capture_output=True)
            return (Path(tmp) / 'Example.class').read_bytes()

    def accepted(self, source):
        report = audit(self.compile(source), 'tick')
        self.assertEqual(report['status'], 'BYTECODE_FILTER_PASS')

    def rejected(self, source, message):
        with self.assertRaisesRegex(AuditError, message):
            audit(self.compile(source), 'tick')

    def test_pure_primitive(self):
        self.accepted('public static int tick(int x) { return x * 3 + 1; }')

    def test_static_transitive(self):
        self.accepted('private static int helper(int x) { return x + 1; } public static int tick(int x) { return helper(x); }')

    def test_reference_allocation(self):
        self.rejected('public static int tick() { Object o = new Object(); return 1; }', 'forbidden opcode')

    def test_primitive_array(self):
        self.rejected('public static int tick() { int[] a = new int[2]; return a[0]; }', 'forbidden opcode')

    def test_unverified_library_call(self):
        self.rejected('public static int tick(int n) { return Math.abs(n); }', 'unverified external call')

    def test_backward_branch(self):
        self.rejected('public static int tick(int n) { int i=0; while(i<n) i++; return i; }', 'backward or zero-distance branch')

    def test_synchronized(self):
        self.rejected('public static synchronized int tick() { return 2; }', 'synchronized/native/abstract')

    def test_reference_parameter(self):
        self.rejected('public static int tick(Object obj) { return 0; }', 'reference arguments')

    def test_initializer(self):
        self.rejected('static { System.setProperty("x", "1"); } public static int tick() { return 1; }', 'class initializer')

    def test_recursion(self):
        self.rejected('public static int tick(int n) { return n == 0 ? 0 : tick(n-1); }', 'recursive call cycle')

    def test_string_constant(self):
        self.rejected('public static int tick() { return "hello".length(); }', 'non-primitive constant')

    def test_dynamic_dispatch(self):
        self.rejected('public static int tick(Object o) { return o.hashCode(); }', 'reference arguments')

    def test_switch(self):
        self.rejected('public static int tick(int x) { switch(x) { case 1: return 2; default: return 3; } }', 'unsupported variable-length')

    def test_malformed_class(self):
        with self.assertRaises(AuditError):
            audit(b'not class', 'tick')


if __name__ == '__main__':
    unittest.main()
