# Serial-actor carrier pool (host prototype)

This module is a Java host-runtime reference implementation, not compiled AttoLang guest code or a certified real-time scheduler.

## Contract

A serial actor is constrained to **at most one executing turn**, not to one permanent OS thread. Multiple fixed carriers share a bounded runnable ring. A turn releases the actor's exclusive execution lease and its state-ownership rights; the next turn may execute on a different carrier. The lease handoff uses the scheduler monitor to provide a happens-before edge across carriers. One actor processes at most one message before yielding to the back of the eligible runnable queue.

The admission path uses preallocated primitive mailbox rings, coalesced runnable tokens, and generation-checked actor handles. It rejects a full mailbox or stale actor handle without allocating a message envelope. Each consistent snapshot must satisfy:

    accepted = processed + failedTurns + abandoned + queued + inFlight

Stopping an actor with drain=true refuses new mail but drains admitted turns; drain=false abandons queued turns while allowing any current turn to finish. Runtime shutdown rejects new sends and attempts bounded carrier joins. Fatal actor faults preserve the last committed state and publish a bounded supervisor-only CONTROL notice; overflow increments an explicit escalation counter.

## Run the executable host probe

With OpenJDK 21 or 25, from repository root:

    mkdir -p build/serial-host
    javac -d build/serial-host src/main/java/org/attolang/runtime/SerialCarrierScheduler.java src/test/java/SerialCarrierSchedulerProbe.java
    java -ea -cp build/serial-host SerialCarrierSchedulerProbe

Probes cover live in-flight accounting, full capacity refusal, healthy-neighbor progress, aborted queued turns, slot-generation reuse, actor fault and control notice delivery, control-lane overflow escalation, 12,000 concurrent serial turns across four producers and four carriers, and deterministic shutdown.

Thread migration is supported, **not required** to happen in a particular test run. The probe reports whether it was observed. This scheduler does not start one dedicated thread per actor.

## Explicit limitations

- This is a HOST_ONLY Java reference. The AttoLang frontend does not emit guest code using it.
- There is no NIO selector or reactor, transport mailman, real supervisor select loop, or parent-supervisor escalation yet.
- LongBinaryOperator handlers may block, allocate or run indefinitely. There is no enforced per-turn fuel or wall-clock deadline.
- Java monitors, ordinary OS threads and timeout joins do not establish WCET or a hard-RT guarantee.
- No p99.9/p99.99 latency claim exists; a soft-RT claim needs measured target-specific workload and overload envelopes.
- Native/RTOS hard-RT candidate profiles need separate bounded-allocation, interrupt, preemption, FFI and schedulability proofs.

Specification: https://github.com/atto-lang/atto-spec/pull/17
Further subsystem implementation: https://github.com/atto-lang/atto-runtime.realtime-java/issues/3

**Verification policy:** local host probes are not compiled guest conformance. Empty-step GitHub Actions results are NOT_EXECUTED, not passed. Hard real-time remains NOT_CERTIFIED.
