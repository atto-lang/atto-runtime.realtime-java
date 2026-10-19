#!/usr/bin/env python3
"""Conservative JVM bytecode admission filter; NOT a WCET or RTSJ safety proof."""
import argparse
import json
import re
import struct
from pathlib import Path


class AuditError(Exception):
    pass


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        if n < 0 or self.pos + n > len(self.data):
            raise AuditError("truncated class file")
        out = self.data[self.pos:self.pos + n]
        self.pos += n
        return out

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def u4(self):
        return struct.unpack('>I', self.take(4))[0]


def read_class(data):
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise AuditError('not a class file')
    r.u2()  # minor
    version = r.u2()
    cp = [None] * r.u2()
    i = 1
    while i < len(cp):
        tag = r.u1()
        if tag == 1:
            n = r.u2()
            cp[i] = (tag, r.take(n).decode('utf-8', errors='replace'))
        elif tag in (3, 4):
            cp[i] = (tag, r.take(4))
        elif tag in (5, 6):
            cp[i] = (tag, r.take(8))
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            cp[i] = (tag, r.u2())
        elif tag in (9, 10, 11, 12, 17, 18):
            cp[i] = (tag, r.u2(), r.u2())
        elif tag == 15:
            cp[i] = (tag, r.u1(), r.u2())
        else:
            raise AuditError(f'unknown constant pool tag {tag}')
        i += 1

    def val(i, kind=None):
        if i <= 0 or i >= len(cp) or cp[i] is None:
            raise AuditError('bad constant pool index')
        item = cp[i]
        if kind is not None and item[0] != kind:
            raise AuditError('unexpected constant pool tag')
        return item

    access = r.u2()
    self_class = val(val(r.u2(), 7)[1], 1)[1]
    r.u2()  # superclass
    for _ in range(r.u2()):
        r.u2()  # interfaces

    def skip_attributes():
        for _ in range(r.u2()):
            val(r.u2(), 1)
            r.take(r.u4())

    for _ in range(r.u2()):
        r.u2(); r.u2(); r.u2()
        skip_attributes()
    methods = {}
    for _ in range(r.u2()):
        flags = r.u2()
        name, desc = val(r.u2(), 1)[1], val(r.u2(), 1)[1]
        code, handlers = None, 0
        for _ in range(r.u2()):
            aname = val(r.u2(), 1)[1]
            sub = Reader(r.take(r.u4()))
            if aname == 'Code':
                sub.u2(); sub.u2()
                code = sub.take(sub.u4())
                handlers = sub.u2()
                sub.take(8 * handlers)
                # Nested code attributes are irrelevant for this instruction audit.
        methods[(name, desc)] = (flags, code, handlers)
    skip_attributes()
    if r.pos != len(data):
        raise AuditError('trailing bytes in class file')
    return version, self_class, cp, methods


# Fixed-size instructions with immediate operand bytes.
OPS_1 = {0x10, 0x12, *range(0x15, 0x1a), *range(0x36, 0x3b), 0xa9, 0xbc}
OPS_2 = {0x11, 0x13, 0x14, 0x84, *range(0x99, 0xa9), 0xc6, 0xc7,
         *range(0xb2, 0xb9), 0xbb, 0xbd, 0xc0, 0xc1}
OPS_3 = {0xb9, 0xba, 0xc5}
OPS_4 = {0xc8, 0xc9}
BRANCH2 = {*range(0x99, 0xa9), 0xc6, 0xc7}
BRANCH4 = {0xc8, 0xc9}
# Reject reference-bearing opcodes, allocation, monitors, arrays, exceptions, and dynamic dispatch.
FORBIDDEN = {
    0x01, *range(0x19, 0x1a), *range(0x2a, 0x2e), *range(0x2e, 0x36),
    0x3a, *range(0x4b, 0x4f), *range(0x4f, 0x57), 0xb0,
    *range(0xb2, 0xb7), 0xb7, 0xb9, 0xba, 0xbb, 0xbc, 0xbd, 0xbe,
    0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
    0xa5, 0xa6, 0xa8, 0xa9, 0xc9
}
# aload/astore with 'wide' and the non-short forms are included above.
PRIMITIVE_DESC = re.compile(r'\([ZBCSIJFD]*\)[VZBCSIJFD]$')


def inspect_code(code, cp):
    calls = []
    pc = 0
    while pc < len(code):
        op = code[pc]
        if op in (0xaa, 0xab, 0xc4):
            raise AuditError(f'unsupported variable-length instruction at byte {pc}')
        n = 1 + (1 if op in OPS_1 else 2 if op in OPS_2 else 3 if op in OPS_3 else 4 if op in OPS_4 else 0)
        if pc + n > len(code):
            raise AuditError(f'truncated instruction at byte {pc}')
        if op in FORBIDDEN:
            raise AuditError(f'forbidden opcode 0x{op:02x} at byte {pc}')
        if op in (0x12, 0x13, 0x14):
            idx = code[pc + 1] if op == 0x12 else int.from_bytes(code[pc + 1:pc + 3], 'big')
            if not (0 < idx < len(cp) and cp[idx] and cp[idx][0] in (3, 4, 5, 6)):
                raise AuditError(f'non-primitive constant at byte {pc}')
        if op in BRANCH2:
            offset = int.from_bytes(code[pc + 1:pc + 3], 'big', signed=True)
            if offset <= 0:
                raise AuditError(f'backward or zero-distance branch at byte {pc}; WCET not proven')
        if op in BRANCH4:
            offset = int.from_bytes(code[pc + 1:pc + 5], 'big', signed=True)
            if offset <= 0:
                raise AuditError(f'backward or zero-distance branch at byte {pc}; WCET not proven')
        if op == 0xb8:  # invokestatic, must resolve within same class
            idx = int.from_bytes(code[pc + 1:pc + 3], 'big')
            if not (0 < idx < len(cp) and cp[idx] and cp[idx][0] == 10):
                raise AuditError('invokestatic without Methodref')
            klass, nat = cp[idx][1:]
            try:
                owner = cp[cp[klass][1]][1]
                name, desc = (cp[j][1] for j in cp[nat][1:])
            except (IndexError, TypeError, KeyError) as exc:
                raise AuditError('malformed method reference') from exc
            calls.append((owner, name, desc))
        if op in (0xca, 0xfe, 0xff) or 0xcb <= op <= 0xfd:
            raise AuditError(f'reserved opcode 0x{op:02x} at byte {pc}')
        pc += n
    return calls


def audit(data, entry):
    version, owner, cp, methods = read_class(data)
    roots = [k for k in methods if k[0] == entry]
    if len(roots) != 1:
        raise AuditError(f'entry {entry} must resolve to exactly one method (found {len(roots)})')
    if ('<clinit>', '()V') in methods:
        raise AuditError('class initializer may execute before entry; not admitted')
    done, active = set(), set()

    def visit(key):
        if key in active:
            raise AuditError(f'recursive call cycle: {key[0]}')
        if key in done:
            return
        if key not in methods:
            raise AuditError(f'unknown callee {key}')
        flags, code, handlers = methods[key]
        if not flags & 0x0008 or flags & (0x0100 | 0x0020 | 0x0400):
            raise AuditError(f'{key[0]} is not static, or is synchronized/native/abstract')
        if not PRIMITIVE_DESC.fullmatch(key[1]):
            raise AuditError(f'{key[0]} has reference arguments or return type')
        if code is None or handlers:
            raise AuditError(f'{key[0]} has no code or has exception handlers')
        active.add(key)
        for called_owner, name, desc in inspect_code(code, cp):
            if called_owner != owner:
                raise AuditError(f'unverified external call {called_owner}.{name}{desc}')
            visit((name, desc))
        active.remove(key)
        done.add(key)

    visit(roots[0])
    return {'status': 'BYTECODE_FILTER_PASS', 'assurance': 'syntactic-only',
            'class': owner, 'entry': entry, 'classfile_major': version,
            'audited_methods': sorted(k[0] + k[1] for k in done),
            'limitations': ['No WCET or scheduling proof', 'No VM/RTSJ/OS qualification',
                            'No control-flow or dataflow proof beyond opcode checks']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('classfile', type=Path)
    parser.add_argument('--entry', required=True, help='Unique static, primitive-only entry method')
    args = parser.parse_args()
    try:
        report = audit(args.classfile.read_bytes(), args.entry)
        print(json.dumps(report, indent=2))
    except (AuditError, OSError) as exc:
        print(json.dumps({'status': 'REJECT', 'reason': str(exc)}, indent=2))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
