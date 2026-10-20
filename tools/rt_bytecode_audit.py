#!/usr/bin/env python3
"""Fail-closed primitive Java class-file admission gate, not a WCET/no-heap proof.

The deliberately restrictive class-file 52 gate admits only static primitive
call graphs and DAG bytecode. A pass never certifies JVM verification, RTSJ
scope safety, execution timing, or VM/OS/CPU configuration.
"""
import argparse
import hashlib
import json
import re
import struct
from pathlib import Path

MAX_CLASS_BYTES = 2 * 1024 * 1024
MAX_CODE_BYTES = 65535
MAX_CALL_DEPTH = 128
CLASSFILE_MAJOR = 52  # --release 8; vendor acceptance must be established separately
ACC_STATIC = 0x0008
ACC_SYNCHRONIZED = 0x0020
ACC_NATIVE = 0x0100
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
PRIMITIVE_DESC = re.compile(r'\([ZBCSIJFD]*\)[VZBCSIJFD]\Z', re.ASCII)


class AuditError(Exception):
    pass


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        if n < 0 or self.pos + n > len(self.data):
            raise AuditError('truncated or invalid class-file length')
        out = self.data[self.pos:self.pos + n]
        self.pos += n
        return out

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack('>H', self.take(2))[0]

    def u4(self):
        return struct.unpack('>I', self.take(4))[0]

    def finished(self, description):
        if self.pos != len(self.data):
            raise AuditError(f'trailing bytes in {description}')


def read_class(data):
    if len(data) > MAX_CLASS_BYTES:
        raise AuditError('class file exceeds maximum audit size')
    r = Reader(data)
    if r.u4() != 0xCAFEBABE:
        raise AuditError('not a class file')
    minor, version = r.u2(), r.u2()
    if minor != 0 or version != CLASSFILE_MAJOR:
        raise AuditError(f'unsupported class-file version {version}.{minor}; require 52.0')
    cp = [None] * r.u2()
    if len(cp) < 1:
        raise AuditError('empty constant pool')
    i = 1
    while i < len(cp):
        tag = r.u1()
        if tag == 1:
            n = r.u2()
            try:
                cp[i] = (tag, r.take(n).decode('utf-8', errors='strict'))
            except UnicodeDecodeError as exc:
                raise AuditError('malformed UTF-8 constant') from exc
        elif tag in (3, 4):
            cp[i] = (tag, r.take(4))
        elif tag in (5, 6):
            cp[i] = (tag, r.take(8))
            if i + 1 >= len(cp):
                raise AuditError('invalid double-slot constant')
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

    def val(index, kind=None):
        if not isinstance(index, int) or index < 1 or index >= len(cp) or cp[index] is None:
            raise AuditError('bad constant pool index')
        item = cp[index]
        if kind is not None and item[0] != kind:
            raise AuditError('unexpected constant pool tag')
        return item

    def utf(index):
        return val(index, 1)[1]

    def class_name(index):
        return utf(val(index, 7)[1])

    def verification_type(body):
        tag = body.u1()
        if tag > 8:
            raise AuditError('invalid StackMapTable verification type')
        if tag in (7, 8):
            body.u2()

    def parse_stack_map(body):
        for _ in range(body.u2()):
            frame = body.u1()
            if frame <= 63:
                continue
            if frame <= 127:
                verification_type(body)
            elif frame == 247:
                body.u2()
                verification_type(body)
            elif 248 <= frame <= 251:
                body.u2()
            elif 252 <= frame <= 254:
                body.u2()
                for _ in range(frame - 251):
                    verification_type(body)
            elif frame == 255:
                body.u2()
                for _ in range(body.u2()):
                    verification_type(body)
                for _ in range(body.u2()):
                    verification_type(body)
            else:
                raise AuditError('invalid StackMapTable frame')

    def attrs(reader, allowed):
        result = {}
        for _ in range(reader.u2()):
            name = utf(reader.u2())
            body = Reader(reader.take(reader.u4()))
            if name not in allowed or name in result:
                raise AuditError(f'duplicate or unsupported attribute {name}')
            if name == 'Code':
                max_stack, max_locals = body.u2(), body.u2()
                code = body.take(body.u4())
                if not code or len(code) > MAX_CODE_BYTES:
                    raise AuditError('empty or oversized Code')
                handlers = body.u2()
                if handlers:
                    raise AuditError('exception handlers not admitted')
                # Zero table already consumed. Allow only optional line-number tables.
                attrs(body, {'LineNumberTable', 'StackMapTable'})
                result[name] = (code, max_stack, max_locals)
            elif name == 'LineNumberTable':
                for _ in range(body.u2()):
                    pc, _line = body.u2(), body.u2()
                    # Source-only metadata cannot change execution; its structure must still parse.
                    if pc > MAX_CODE_BYTES:
                        raise AuditError('invalid line-number position')
                result[name] = None
            elif name == 'StackMapTable':
                parse_stack_map(body)
                result[name] = None
            elif name == 'SourceFile':
                utf(body.u2())
                result[name] = None
            elif name in ('Deprecated', 'Synthetic'):
                result[name] = None
            body.finished(name)
        return result

    access = r.u2()
    if access & ACC_INTERFACE:
        raise AuditError('interfaces not admitted')
    owner = class_name(r.u2())
    if owner.startswith('[') or not owner or '\x00' in owner:
        raise AuditError('invalid audited class name')
    if class_name(r.u2()) != 'java/lang/Object':
        raise AuditError('only direct java/lang/Object superclass admitted')
    if r.u2():
        raise AuditError('interfaces not admitted')
    if r.u2():
        raise AuditError('fields not admitted (including static fields)')

    methods = {}
    for _ in range(r.u2()):
        flags = r.u2()
        name, desc = utf(r.u2()), utf(r.u2())
        method_attrs = attrs(r, {'Code', 'Deprecated', 'Synthetic'})
        key = (name, desc)
        if key in methods:
            raise AuditError('duplicate method declaration')
        methods[key] = (flags, method_attrs.get('Code'))
    attrs(r, {'SourceFile', 'Deprecated', 'Synthetic'})
    r.finished('class file')
    return owner, cp, methods, val


# Explicit allowlist; never treat a new/unknown opcode as a one-byte nop.
# Fixed operand sizes (not counting opcode), where values 0..4.
ALLOWED = {op: 0 for op in [0x00, *range(0x02, 0x10),
           *range(0x1a, 0x2a), *range(0x3b, 0x4b),
           *range(0x57, 0x84), *range(0x85, 0x99),
           *range(0xac, 0xb0), 0xb1] if op not in {0x6c, 0x6d, 0x70, 0x71}}
ALLOWED.update({op: 1 for op in (0x10, 0x12, *range(0x15, 0x19), *range(0x36, 0x3a))})
ALLOWED.update({op: 2 for op in (0x11, 0x13, 0x14, 0x84,
               *range(0x99, 0xa5), 0xa7, 0xb8)})
ALLOWED[0xc8] = 4
BRANCH2 = set(range(0x99, 0xa5)) | {0xa7}
BRANCH4 = {0xc8}
CONDITIONAL = BRANCH2 - {0xa7}
TERMINALS = set(range(0xac, 0xb0)) | {0xb1}
RETURN_TYPE = {0xac: 'I', 0xad: 'J', 0xae: 'F', 0xaf: 'D', 0xb1: 'V'}
# Java uses I returns for boolean, byte, char and short.
INT_RETURN_TYPES = set('IZBCS')
LOCAL_LOADS = {0x15: 1, 0x16: 2, 0x17: 1, 0x18: 2}
LOCAL_STORES = {0x36: 1, 0x37: 2, 0x38: 1, 0x39: 2}


def inspect_code(code, cp, val, method_desc, max_locals):
    """Decode every instruction, validate forward-only CFG, return a graph/call map."""
    instructions = {}
    pc = 0
    while pc < len(code):
        op = code[pc]
        if op not in ALLOWED:
            raise AuditError(f'unsupported variable-length instruction at byte {pc}' if op in (0xaa, 0xab, 0xc4) else f'forbidden opcode or unknown instruction 0x{op:02x} at byte {pc}')
        end = pc + 1 + ALLOWED[op]
        if end > len(code):
            raise AuditError(f'truncated instruction at byte {pc}')
        operand = code[pc + 1:end]
        target = None
        called = None
        if op in (0x12, 0x13, 0x14):
            index = operand[0] if op == 0x12 else int.from_bytes(operand, 'big')
            tag = val(index)[0]
            if tag not in ((5, 6) if op == 0x14 else (3, 4)):
                raise AuditError(f'non-primitive constant or tag mismatch at byte {pc}')
        if op in LOCAL_LOADS or op in LOCAL_STORES or op == 0x84:
            index = operand[0]
            width = (LOCAL_LOADS | LOCAL_STORES).get(op, 1)
            if index + width > max_locals:
                raise AuditError(f'local-variable index exceeds max_locals at byte {pc}')
        if 0x1a <= op <= 0x29:  # implicit iload/lload/fload/dload 0..3
            width = 2 if 0x1e <= op <= 0x21 or 0x26 <= op <= 0x29 else 1
            if (op - 0x1a) % 4 + width > max_locals:
                raise AuditError(f'implicit load exceeds max_locals at byte {pc}')
        if 0x3b <= op <= 0x4a:
            width = 2 if 0x3f <= op <= 0x42 or 0x47 <= op <= 0x4a else 1
            if (op - 0x3b) % 4 + width > max_locals:
                raise AuditError(f'implicit store exceeds max_locals at byte {pc}')
        if op in BRANCH2 | BRANCH4:
            offset = int.from_bytes(operand, 'big', signed=True)
            if offset <= 0:
                raise AuditError(f'backward or zero-distance branch at byte {pc}; WCET not proven')
            target = pc + offset
        if op == 0xb8:
            idx = int.from_bytes(operand, 'big')
            klass, nat = val(idx, 10)[1:]
            owner = val(val(klass, 7)[1], 1)[1]
            name_idx, desc_idx = val(nat, 12)[1:]
            called = (owner, val(name_idx, 1)[1], val(desc_idx, 1)[1])
        if op in TERMINALS:
            declared_return = method_desc.split(')', 1)[-1]
            if not (RETURN_TYPE[op] == declared_return or
                    (op == 0xac and declared_return in INT_RETURN_TYPES)):
                raise AuditError(f'return opcode does not match method descriptor at byte {pc}')
        instructions[pc] = (op, end, target, called)
        pc = end
    for start, (_, end, target, _) in instructions.items():
        if target is not None and target not in instructions:
            raise AuditError(f'branch target is not an instruction boundary at byte {start}')

    return instructions


def audit(data, entry):
    owner, cp, methods, val = read_class(data)
    roots = [key for key in methods if key[0] == entry]
    if len(roots) != 1:
        raise AuditError(f'entry {entry} must resolve to exactly one method (found {len(roots)})')
    if any(name == '<clinit>' for name, _ in methods):
        raise AuditError('class initializer may execute before entry; not admitted')
    analyzed = {}
    active = set()

    def visit(key):
        if key in active:
            raise AuditError(f'recursive call cycle: {key[0]}')
        if key in analyzed:
            return analyzed[key]
        if key not in methods:
            raise AuditError(f'unknown callee {key}')
        flags, code_info = methods[key]
        if not flags & ACC_STATIC or flags & (ACC_NATIVE | ACC_SYNCHRONIZED | ACC_ABSTRACT):
            raise AuditError(f'{key[0]} is not static, or is synchronized/native/abstract')
        if not PRIMITIVE_DESC.fullmatch(key[1]):
            raise AuditError(f'{key[0]} has reference arguments or return type')
        if code_info is None:
            raise AuditError(f'{key[0]} has no Code attribute')
        code, max_stack, max_locals = code_info
        if len(active) >= MAX_CALL_DEPTH:
            raise AuditError('static call chain exceeds conservative analysis-depth limit')
        active.add(key)
        try:
            instructions = inspect_code(code, cp, val, key[1], max_locals)
            # Resolve every syntactically present call, including unreachable code.
            costs = {}
            depths = {}
            for pc, (_, _, _, called) in instructions.items():
                if called is not None:
                    called_owner, name, desc = called
                    if called_owner != owner:
                        raise AuditError(f'unverified external call {called_owner}.{name}{desc}')
                    costs[pc], depths[pc] = visit((name, desc))
            # All branches are strictly forward: bottom-up DAG evaluation
            # avoids Python recursion even for large unrolled critical sections.
            bounds = {}
            for pc in sorted(instructions, reverse=True):
                op, end, target, _ = instructions[pc]
                if op in TERMINALS:
                    bounds[pc] = (1, 1)
                    continue
                next_pcs = ([target, end] if op in CONDITIONAL else
                            [target] if target is not None else [end])
                if any(nxt not in bounds for nxt in next_pcs):
                    raise AuditError('reachable control flow falls off end of Code')
                rest = [bounds[nxt] for nxt in next_pcs]
                child_cost = costs.get(pc, 0)
                child_depth = depths.get(pc, 0)
                bounds[pc] = (1 + child_cost + max(x[0] for x in rest),
                              max([1 + child_depth] + [x[1] for x in rest]))
            result = bounds[0]
            analyzed[key] = result
            return result
        finally:
            active.remove(key)

    max_ops, max_depth = visit(roots[0])
    return {'status': 'BYTECODE_FILTER_PASS', 'assurance': 'syntactic-only',
            'class': owner, 'entry': entry, 'classfile_major': CLASSFILE_MAJOR,
            'audited_methods': sorted(n + d for n, d in analyzed),
            'input_sha256': hashlib.sha256(data).hexdigest(),
            'contract_id': 'oreslang-jvm-primitive-dag-v1',
            'max_bytecodes_per_invocation': max_ops,
            'max_static_call_depth': max_depth,
            'limitations': [
                'Instruction counts are not WCET; opcode timing is VM/CPU-dependent',
                'No JVM bytecode-verifier or full type/stack proof',
                'No JVM heap provenance, RTSJ region, GC, or scheduling proof',
                'No target OS/AOT/toolchain qualification',
            ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('classfile', type=Path)
    parser.add_argument('--entry', required=True)
    parser.add_argument('--max-bytecodes', type=int, default=None,
                        help='reject if transitive worst-path instruction count exceeds this limit')
    parser.add_argument('--max-call-depth', type=int, default=None,
                        help='reject if transitive static call depth exceeds this limit')
    args = parser.parse_args()
    try:
        if args.max_bytecodes is not None and args.max_bytecodes < 0:
            raise AuditError('--max-bytecodes must be nonnegative')
        if args.max_call_depth is not None and args.max_call_depth < 1:
            raise AuditError('--max-call-depth must be positive')
        report = audit(args.classfile.read_bytes(), args.entry)
        if (args.max_bytecodes is not None and
                report['max_bytecodes_per_invocation'] > args.max_bytecodes):
            raise AuditError('instruction count exceeds --max-bytecodes budget')
        if (args.max_call_depth is not None and
                report['max_static_call_depth'] > args.max_call_depth):
            raise AuditError('static call depth exceeds --max-call-depth budget')
        print(json.dumps(report, indent=2))
    except (AuditError, OSError, RecursionError) as exc:
        print(json.dumps({'status': 'REJECT', 'reason': str(exc)}, indent=2))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
