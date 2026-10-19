# Oreslang real-time compatibility contract — v0 draft

Status: **design proposal**. This document does not claim conformance, hard-RT certification or production readiness.

## Profiles

- `general`: ordinary Oreslang actor/Task execution; allocation, JIT, GC and dynamic code loading permitted.
- `soft-rt`: explicit latency objectives, preallocation, bounded queue/backpressure behavior, priority-aware scheduling, observable GC interference, configurable miss policy. Misses possible; measured tail latency is **not** a guarantee.
- `hard-rt`: admitted critical call graph with proven/contracted bounds for allocation, WCET, stack, blocking, queue occupancy, finalization, task admission and maximum response time on an identified target. Fail closed on unknown effects. No JIT/deoptimization, class loading or ordinary heap access in strict RTSJ no-heap regions.

## Admission gates (all mandatory for hard-RT)

1. **Typed source IR**: transitive effects and numeric resource bounds: `alloc(region, max_bytes)`, `copy(max_bytes)`, `heap_access`, `suspend`, `spawn`, `throw`, `io`, `lock(max_blocking)`, `ffi`, `dynamic_load` and `nondeterministic`. Missing callee contracts are unknown, not zero. Bounded loops/recursion and stack depth; no unknown dynamic-dispatch targets.
2. **Ownership and regions**: actor-local mutable state, owned arena capacity, scoped-memory lifetime/provenance, nested scope/escape restrictions, domain-limited immutable sharing; no heap-to-scoped or longer-lived-to-shorter-lived dangling references. Scratch reset costs and cleanup/destructors must be bounded. Logical actor ownership alone does not provide GC independence.
3. **Runtime boundaries**: fixed-capacity mailboxes, preallocated task/timer/exception metadata, bounded queue operations, explicit overflow/admission policies, priority-inversion bounds, no unverified task/blocking operations, no hot reload or compiler work in critical phase. `await` external I/O is not bounded without a separately established timeout/response contract.
4. **Backend audit**: verify source-to-bytecode/native effects after lowering, including boxing, closures, string formatting, class initialization, exception/unwinding code, array copies and FFI. Unknown target calls reject. Record target Java classfile compatibility and AOT transformation.
5. **System evidence**: WCET bound and response-time feasibility with known interference, CPU/RTOS/VM/GC configuration, interrupts/threads, memory hierarchy/cache policy, target load, and overload policy. Stress/fault-injection measurements corroborate — never replace — proof/contractual analysis.

A compiler result must distinguish `proven`, `contracted`, `measured`, and `unknown` for **each** resource and assumption. Hard-RT admission rejects `unknown` and does not silently upgrade `measured` to `proven`.

## Memory details

RTSJ `LTMemory` outside the Java heap can avoid collector tracing, but Java code may still hold invalid heap references unless verified. A `NoHeapRealtimeThread` may not even *reference* regular heap objects, not just allocate. Scoped objects cannot escape into longer-lived locations. Initial/max capacity should be fixed and validated before dispatch. Versioned code data or immutable cross-actor objects require explicit safe allocation domains and bounded lifetime reclamation.

A separate logical heap per actor does **not** establish physical RTSJ scope, isolation from Java GC, or constant-time arena reset. Normal Java objects allocated as the actor's state still belong to the JVM heap unless the target VM offers real allocation-area controls.

## Explicit restrictions in v0 hard-RT Java proof of concept

The initial bytecode auditor intentionally admits only primitive arguments/results and primitive-only static method graphs within a single class with no class initializer. It rejects arrays, object references, fields, exception handlers, monitors, loops with back edges, recursion, reflection, dynamic dispatch, native/unverified calls and class loading. These exclusions are initially stricter than necessary; later relax them only with documented region provenance, bounded-flow proof and VM API contracts.

## References

- [RTSJ NoHeapRealtimeThread](https://rtsj.org/specjavadoc/javax/realtime/NoHeapRealtimeThread.html)
- [RTSJ memory lifetime restrictions](https://www.rtsj.org/specjavadoc/design.html)
- [aicas JamaicaVM 8.11 RTSJ 2.0 LTMemory](https://www.aicas.com/jamaica/8.11/doc/rtsj_api/javax/realtime/memory/LTMemory.html)

## Verified bytecode admission filter v2 (portable prototype)

This is a **necessary only** syntactic gate, not a JVM verifier, a WCET proof or
RTSJ/Java no-heap certification. Audit the exact SHA-256 of any classfile before
claiming an instruction count bound. The `max_bytecodes_per_invocation` output
counts transitive calls along the worst *forward-only* control-flow path; it is
not nanoseconds, cycles, memory allocation, or response-time bounds.

For portability and to avoid accidentally admitting future JVM features, this
prototype only accepts Java 8 / classfile 52.0 compiled for a direct subclass
of `java/lang/Object`, with no class fields, interfaces, class initializers,
exception handlers or unsupported attributes. Its call graph must be static,
primitive-only and intra-class. Integer division/remainder are deliberately
rejected because their exceptional paths may allocate/throw. A strict opcode
allowlist and instruction-boundary control-flow checks reject unknown code.

Examples:

```sh
javac --release 8 -g:none -d /tmp/rtbuild Example.java
python3 tools/rt_bytecode_audit.py /tmp/rtbuild/Example.class --entry tick \
  --max-bytecodes 200 --max-call-depth 3
```

An audited PASS **must not be promoted** to a hard-RT certificate even if the
budgets pass. Additional required gates include bytecode type verification,
exceptional-path analysis, initialization/warmup, transitive heap provenance,
scoped region correctness, VM/AOT code inspection, priority and interrupt
interference analysis, and machine-specific WCET/schedulability evidence.

The parser has a finite size budget and fuzz/malformed regression suite. It
intentionally rejects JVM classfile versions and language constructs whose
semantics have not been explicitly admitted.
