# Oreslang real-time Java runtime (incubating)

This repository houses the **JVM-specific real-time qualification layer** for Oreslang. It is **not** a replacement for `oreslang-source.java` or `oreslang-source.llvm`. Core ownership, effects, and resource budgets belong in the shared Oreslang language/compiler contract; the runtime adapts those contracts to particular JVMs and target systems.

## Current state

- `tools/rt_bytecode_audit.py`: dependency-free, **fail-closed syntactic admission filter** for a deliberately restricted primitive-only Java bytecode entry point. It detects obvious forbidden allocation, heap-reference, monitor, dynamic-call and unbounded-loop risks. It follows verified local static calls. **A PASS IS NOT A HARD-REAL-TIME OR NO-HEAP CERTIFICATE**. It does not prove WCET, object provenance, safe regions, VM GC independence, or RTOS scheduling.
- `tests/`: Javac-based acceptance/rejection regression suite with no proprietary VM SDK requirement.
- `docs/`: normative proposed runtime contract, memory-lifetime rules, required proof gates and VM integration/qualification plan.

```sh
python3 -m unittest discover -s tests -v
javac --release 8 -g:none -d /tmp Example.java
python3 tools/rt_bytecode_audit.py /tmp/Example.class --entry tick
```

In this bootstrap, an audited entry must be one unique `static` method with primitive arguments/return, and any reachable `invokestatic` must target the same class. A class initializer, exception handler, backward branch, array, object reference, dynamic dispatch, monitor, or unverified external call is rejected. This narrow contract is **intentional** while the compiler and VM-target proofs are not implemented. Passing the filter is only one necessary (not sufficient) hard-RT gate.

## Platform work

- JamaicaVM: implement a version-pinned RTSJ 2.0 scoped-memory/no-heap adapter after securing the exact supported vendor SDK, runtime version, AOT compiler and licensed CI runner. Do not mix old `javax.realtime.LTMemory` with the RTSJ 2.0 `javax.realtime.memory.LTMemory` API.
- PTC Perc: establish supported Java/classfile/API versions and document its actual memory, AOT, and scheduling guarantees before implementing an adapter.
- Standard JVM/GraalVM: soft-RT baseline; profiling alone is never sufficient for hard-RT certification.

## Safety, security, and reproducibility

Hard-RT admission is **target-bound** to a VM build, AOT toolchain, RTOS, CPU, scheduling configuration, and certified library set. No proprietary SDK, license, CI credentials or source mirroring is required by the portable tests. A production certificate requires backend inspection, transitive effects and memory-region proof, platform-specific WCET/response-time analysis, and on-target stress validation. See `docs/real-time-contract.md` and `docs/qualification.md`.

## v2 audit hardening

The portable bytecode auditor now uses a strict opcode allowlist, classfile 52.0
pinning, exact attribute decoding, forward-only CFG boundary validation,
transitive static call counting, and SHA-256 evidence binding. It fails closed
on hidden class initializers, fields, unusual class inheritance, malformed
bytecode, and integer division/remainder (exceptional allocation path).

The optional `--max-bytecodes N` and `--max-call-depth N` flags reject code
that exceeds explicitly provided source-independent syntactic budgets. The
reported instruction bound is **not** time, WCET or an RTSJ heap-independence
certificate.

```sh
python3 -m unittest discover -s tests -v
python3 tools/rt_bytecode_audit.py /path/Example.class --entry tick \
  --max-bytecodes 1000 --max-call-depth 16
```
