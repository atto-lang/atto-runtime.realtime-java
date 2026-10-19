# VM integration and qualification plan

## Gate 0 — portable tests (implemented)

`python3 -m unittest discover -s tests -v` builds `javac --release 8` fixtures and validates conservative bytecode pass/reject behavior. Unit tests prove only what the fixture checks demonstrate; they are not evidence for hard-RT execution.

## Gate 1 — compiler integration (pending in source.java)

Generate plain JVM bytecode from typed Oreslang IR, independent of Graal/Truffle JIT runtime APIs. Emit an effects/resource manifest, direct-call graph and stable content hashes for each code unit. Reject unknown effects in critical routines. Preserve `core` / `alloc` / `stdlib` boundaries and actor arena ownership. Use release level supported by the target VM, not automatically the Java 21 Truffle toolchain target.

## Gate 2 — memory (pending vendor SDK)

On JamaicaVM RTSJ 2.0, construct a precommitted scoped `LTMemory` and instantiate the actor/thread/entry logic in legally accessible areas. Exercise `NoHeapRealtimeThread` with heap-reference violation negative tests; audit crossing into `javax.realtime.memory` and shared code metadata. A passing primitive-only bytecode filter does not certify thread construction or scheduler setup.

On Perc, separately verify whether its memory model can provide an equivalent no-heap or otherwise bounded-GC contract. Never assume RTSJ parity.

## Gate 3 — AOT and scheduling (pending vendor SDK + actual RT target)

- Pin SDK, AOT compiler, VM, OS/RTOS, CPU ISA, firmware/interrupt policy, affinity and scheduler settings.
- Eliminate class loading/JIT/GC interference and hidden lazy initialization from admitted critical path, or bound them as target-specific requirements permit.
- Analyze periodic workloads using computation WCET, release jitter, deadline, priority, blocking bound, queue occupancy and resource contention. External asynchronous I/O must be separately bounded or isolated.
- Produce reproducible evidence/artifacts and run overload, exception, cancellation, actor teardown, hot-code-generation transition and GC-stress scenarios; fail closed on any undocumented dependency.

## Gate 4 — proof manifest (planned schema)

Record `{source_hash, compiler_hash, bytecode_hash, vendor_vm, vm_version, aot_toolchain, os, cpu, profile, contract_assumptions, audited_entry_points, effects, memory_bounds, wcet, blocking_bound, response_time_bound, evidence, unresolved}`. A `BYTECODE_FILTER_PASS` result must never be displayed as `hard_rt_verified`.

## Non-goals for bootstrap

No attempt to emulate `NoHeapRealtimeThread` on standard OpenJDK, promise deterministic Java GC merely because actors use logical arenas, or bypass proprietary licensing. Do not ship vendor integration as working until vendor SDK and on-target tests succeed.
