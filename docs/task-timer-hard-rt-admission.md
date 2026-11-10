# AttoLang Task timers — hard-real-time admission (draft)

**Status:** Portable policy-only preflight. No vendor-qualified deployment, native clock driver, guest-to-timer lowering, complete Task integration, on-target WCET proof, or hard-RT certification is established by this change.

## Distinguish Timer, Future, Task, and Actor

- A **timer** is an owner-/generation-fenced registration and bounded clock wake. Its expiry is a readiness signal; it is not guest execution.
- A **Future** exposes completion. `await time.sleep(d)` / `await time.after(d)` resumes the **calling Task's existing continuation**, never creates an extra Task or blocks a carrier.
- A **Task** is separately scheduled work with a structured parent scope. It owns any timer registrations created inside its lifetime. Task cancellation requests timer withdrawal and cleanup; logical cancellation is **not** proof of physical quiescence.
- An **Actor** keeps a serialized logical turn during an awaited timer. A clock thread cannot execute a guest callback or bypass actor CONTROL. Actor teardown and hot reload revoke stale timer tokens by id/incarnation and await physical retirement.

Ordinary Task timers are useful for timeouts, backoff, retry, and async sleeping. `time.timeout(duration, sharedFuture)` is an **observer** and must not cancel a producer it doesn't own; a separately defined *owned-child deadline* may cancel children, then wait for their cleanup and retirement before reporting scope drain. Deadline inheritance is minimum(parent, scope, task, actor, tenant) under the same monotonic clock domain.

## Hard RT is a separate qualified execution profile

The `TaskTimerAdmission.evaluate` API runs **outside** the timing-critical path. It composes the existing `HardRealtimeAdmission` VM/AOT/memory/scheduler policy with a timer-specific qualification policy; neither gate may substitute for the other. The evidence must bind to the exact compiled guest artifact and named OS/RTOS, CPU, kernel, IRQ, clock source, scheduler, memory, compiler, vendor VM and runtime build. Boolean declarations are *inputs*, not measured proof.

Required timer system properties:

1. Preallocate and bound timer registrations, ingress, ready tickets, owner quotas and expiry/cancellation bookkeeping. No guest pointers or callbacks on clock/ISR threads. Avoid priority inversion and avoid unbounded producer/consumer queues.
2. Use a **qualified monotonic absolute clock**. Zero delay still resumes on a later authorized Task/actor turn. Reject rollback/overflow, stale generations and unsupported clock domains.
3. Reserve a bounded priority/EDF RT-ready lane. Measure and bound release jitter, queueing, non-preemptible actor sections, claim-to-**first actual guest instruction**, guest WCET, higher-priority interference and cancellation cleanup.
4. Start/finish/cleanup deadlines are **separate** gates. At an exhausted deadline, do not run a supposedly on-time guest effect; report `DeadlineMissed`, apply supervisor policy and retire resources safely. Cleanup time must be budgeted and is not magically instantaneous.
5. Periodic hard-RT releases require **absolute phase** (t0 + n × period), admission/backlog policy and overrun/miss handling. A demand-driven `interval.next()` or `sleep(period)` loop is not equivalent.
6. Unqualified blocking FFI, heap allocation, hidden GC, JIT, lazy worker creation, priority-inverting mutexes, unbounded `defer`/finally cleanup and arbitrary external I/O are **inadmissible** on the hard-RT critical path. Compiler-injected cooperation helps soft scheduling but is not a substitute for proven bounded preemption/WCET.

`TaskTimerAdmission.Budget` checks explicit upper bounds with overflow-safe arithmetic. Its start budget is release jitter + wake queue + nonpreemptible blocking + claim-to-instruction. Its completion budget adds guest WCET and higher-priority interference. Its cancellation budget instead adds worst-case cleanup and interference. A product may need tighter timing models (including nested tasks and resources) before on-target admission; a synthetic passing unit test does **not** qualify an actual device.

## Backend boundaries and verification

- **Java/GraalVM:** ordinary runtime can provide soft-RT timers, but its generic executor/GC and host Native Image do not prove guest hard RT. This repository provides a Java-specific preflight for a *separately qualified RTSJ/native* deployment only. No generic JVM provider is qualified.
- **LLVM:** native fixed-capacity timer queue and actor-ledger integration are still draft work (source.llvm #1123, #1144). Prove actual native actor start, reserved RT dispatch and hardware WCET before enabling hard mode.
- **.NET NativeAOT, Wasm, Forge:** parity remains unproven. Treat RT-critical timer API as unsupported, not silently soft, where the backend lacks a qualified profile.

### Executed acceptance gates

Run the exact-source tests in this module, then independently perform `Task+Timer+Actor` compiled guest tests, actor incarnation/ABA races, cancel-vs-expire, callback saturation, scope close, task-local migration, zero-delay, deadline inheritance, overload, intermittent IRQ interference, timer wrap/overflow, first-instruction trace, missed deadline and physical retirement. Repeat with the exact guest binary on the **named target hardware** and analyze schedulability/WCET, not p99.

Every receipt must include commit + root tree, compiler and guest artifact identity, job/attempt and nonzero executed steps, source versus host test classification, timing assumptions and trace. Zero-step CI and model-only tests remain **unverified** for runtime/hard-RT guarantees.

Related backend-neutral contract: `oreslang-platform-conformance/docs/TIMER_TIMEOUT_OBSERVER_V1.md`. Java time drafts: #2203, #2208. LLVM native timer drafts: #1123, #1144.
