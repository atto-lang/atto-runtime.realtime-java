"""Adversarial classfile tests: mutate valid javac output, never execute payloads."""
import random
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from rt_bytecode_audit import AuditError, audit, read_class


class TestAdversarialClassfile(unittest.TestCase):
    def compile(self, source):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'Example.java'
            path.write_text('public class Example {\n' + source + '\n}\n')
            subprocess.run(['javac', '--release', '8', '-g:none', '-d', tmp, str(path)],
                           capture_output=True, check=True)
            return (Path(tmp) / 'Example.class').read_bytes()

    def patch_code(self, data, pattern, replacement, descriptor='(I)I'):
        _owner, _cp, methods, _val = read_class(data)
        code, _, _ = methods['tick', descriptor][1]
        assert code.count(pattern) == 1, (code.hex(), pattern.hex())
        off = data.find(code)
        assert off >= 0 and data.find(code, off + 1) == -1
        target = off + code.index(pattern)
        buf = bytearray(data)
        buf[target:target + len(pattern)] = replacement
        return bytes(buf)

    def test_forward_branches_are_counted(self):
        data = self.compile('public static int tick(int x) { return x > 0 ? 5 : 3; }')
        report = audit(data, 'tick')
        self.assertGreaterEqual(report['max_bytecodes_per_invocation'], 3)
        self.assertEqual(report['max_static_call_depth'], 1)

    def test_transitive_call_bound(self):
        data = self.compile('private static int inc(int x) { return x+1; } '
                            'public static int tick(int x) { return inc(inc(x)); }')
        report = audit(data, 'tick')
        self.assertEqual(report['max_static_call_depth'], 2)
        self.assertGreater(report['max_bytecodes_per_invocation'], 6)

    def test_branch_into_operand_rejected(self):
        data = self.compile('public static int tick(int x) { return x == 0 ? 5 : 3; }')
        _owner, _cp, methods, _val = read_class(data)
        code = methods['tick', '(I)I'][1][0]
        branch_pc = next(i for i, op in enumerate(code) if op in range(0x99, 0xa5))
        pattern = code[branch_pc:branch_pc + 3]
        # A branch to pc+1 lands inside its own two-byte offset operand.
        mutated = self.patch_code(data, pattern, bytes([pattern[0], 0, 1]))
        with self.assertRaisesRegex(AuditError, 'instruction boundary'):
            audit(mutated, 'tick')

    def test_branch_off_end_rejected(self):
        data = self.compile('public static int tick(int x) { return x == 0 ? 5 : 3; }')
        code = read_class(data)[2]['tick', '(I)I'][1][0]
        branch_pc = next(i for i, op in enumerate(code) if op in range(0x99, 0xa5))
        pattern = code[branch_pc:branch_pc + 3]
        with self.assertRaisesRegex(AuditError, 'instruction boundary'):
            audit(self.patch_code(data, pattern, bytes([pattern[0], 0x7f, 0xff])), 'tick')

    def test_return_type_opcode_rejected(self):
        data = self.compile('public static int tick(int x) { return x + 1; }')
        with self.assertRaisesRegex(AuditError, 'return opcode'):
            audit(self.patch_code(data, b'\xac', b'\xb1'), 'tick')

    def test_unsupported_opcode_rejected(self):
        data = self.compile('public static int tick(int x) { return x + 1; }')
        with self.assertRaisesRegex(AuditError, 'forbidden opcode'):
            audit(self.patch_code(data, b'\x1a', b'\xff'), 'tick')

    def test_invalid_local_index_rejected(self):
        data = self.compile('public static int tick(int x) { return x + 1; }')
        with self.assertRaisesRegex(AuditError, 'max_locals'):
            audit(self.patch_code(data, b'\x1a', b'\x1d'), 'tick')

    def test_division_can_throw_and_allocate_on_exception(self):
        data = self.compile('public static int tick(int x) { return 6 / x; }')
        with self.assertRaisesRegex(AuditError, 'forbidden opcode'):
            audit(data, 'tick')

    def test_static_fields_forbidden_even_unused(self):
        data = self.compile('static int unused; public static int tick(int x) { return x; }')
        with self.assertRaisesRegex(AuditError, 'fields not admitted'):
            audit(data, 'tick')

    def test_wrong_class_version_rejected(self):
        data = bytearray(self.compile('public static int tick(int x) { return x; }'))
        data[7] = 65  # Java 21, outside the deliberately pinned Java 8 contract
        with self.assertRaisesRegex(AuditError, 'unsupported class-file version'):
            audit(bytes(data), 'tick')

    def test_invalid_methodref_index_rejected(self):
        data = self.compile('private static int inc(int x) { return x+1; } '
                            'public static int tick(int x) { return inc(x); }')
        code = read_class(data)[2]['tick', '(I)I'][1][0]
        pc = code.index(0xb8)
        pattern = code[pc:pc + 3]
        with self.assertRaisesRegex(AuditError, 'bad constant pool index'):
            audit(self.patch_code(data, pattern, b'\xb8\xff\xff'), 'tick')

    def test_truncated_files_rejected(self):
        data = self.compile('public static int tick(int x) { return x; }')
        for offset in range(0, len(data), 11):
            with self.subTest(length=offset), self.assertRaises(AuditError):
                audit(data[:offset], 'tick')

    def test_oversized_class_rejected(self):
        data = self.compile('public static int tick(int x) { return x; }')
        with self.assertRaisesRegex(AuditError, 'maximum audit size'):
            audit(data + b'\0' * (2 * 1024 * 1024), 'tick')

    def test_fuzzed_mutations_never_crash_parser(self):
        data = self.compile('public static int tick(int x) { return x > 0 ? x : -x; }')
        rng = random.Random(0xBADC0DE)
        for n in range(250):
            with self.subTest(mutant=n):
                buf = bytearray(data)
                for _ in range(rng.randrange(1, 4)):
                    pos = rng.randrange(len(buf))
                    buf[pos] = rng.randrange(256)
                try:
                    report = audit(bytes(buf), 'tick')
                    self.assertEqual(report['assurance'], 'syntactic-only')
                except AuditError:
                    pass

    def test_extra_classfile_bytes_are_rejected_not_ignored(self):
        valid = self.compile('public static int tick(int x) { return x; }')
        self.assertEqual(audit(valid, 'tick')['assurance'], 'syntactic-only')
        for suffix in (b'\x00', b'garbage', b'\x00' * 100):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(
                    AuditError, 'trailing bytes'):
                audit(valid + suffix, 'tick')

    def test_magic_and_minor_version_mutations_fail_closed(self):
        valid = self.compile('public static int tick(int x) { return x; }')
        invalid_magic = bytearray(valid)
        invalid_magic[0] ^= 0xff
        with self.assertRaisesRegex(AuditError, 'not a class file'):
            audit(bytes(invalid_magic), 'tick')
        nonzero_minor = bytearray(valid)
        nonzero_minor[5] = 1
        with self.assertRaisesRegex(AuditError, 'unsupported class-file version'):
            audit(bytes(nonzero_minor), 'tick')

    def test_overloaded_entry_cannot_silently_select_a_signature(self):
        valid = self.compile(
            'public static int tick(int x) { return x; } '
            'public static long tick(long x) { return x; }'
        )
        with self.assertRaisesRegex(AuditError, 'exactly one method'):
            audit(valid, 'tick')

    def test_class_initializer_is_not_ignored_during_entry_admission(self):
        valid = self.compile(
            'static { long timestamp = System.nanoTime(); } '
            'public static int tick(int x) { return x; }'
        )
        with self.assertRaisesRegex(AuditError, 'class initializer'):
            audit(valid, 'tick')


if __name__ == '__main__':
    unittest.main()
