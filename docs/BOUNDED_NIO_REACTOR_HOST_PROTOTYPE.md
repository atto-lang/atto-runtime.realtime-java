# Bounded NIO reactor / serial-carrier integration (experimental)

**Scope:** Java host runtime reference. This is not compiler-emitted AttoLang guest execution, a production network stack, or hard-real-time scheduling.

## Architecture

A dedicated selector thread owns registrations, readiness, socket reads, cancellation and descriptor retirement. It does **not** run arbitrary actor callbacks. Each successful read is split into unsigned single-byte messages and admitted with `SerialCarrierScheduler.try_send(handle, byte)`; the actor's execution lease remains exclusive to one carrier at a time, but successive turns may execute on different carriers.

The fixed registration table has bounded per-descriptor read buffers. The command ring and ready batch have configured maximum work per dispatch tick. Accepted registration transfers ownership of the nonblocking SocketChannel; capacity or queue-full refusal leaves it with the caller. Handles are `(slot, generation)` so a late cancellation cannot target a reused slot. A stale actor generation is refused by the scheduler rather than silently routed to the successor.

### Backpressure

When mailbox admission reports FULL, the reactor retains every already-read but unadmitted byte in the preallocated descriptor buffer and masks OP_READ. A finite, bounded scan retries paused descriptors after mailbox space becomes available; after the retained bytes are accepted, OP_READ is rearmed. This fallback uses a finite select timeout, **not** a wakeup callback from the scheduler; its latency requires measurement and tuning. It is deliberately limited to ingress OP_READ. No OP_WRITE, stream framing, TLS, half-close semantics, actor-owned buffer lease transport, or TCP send-credit protocol is implemented.

### Cancellation and close

External threads enqueue cancellation commands with their original registration generation. The selector owner applies cancellation and closes the socket exactly once; cancellation ACCEPTED means *queued*, not synchronously completed. Shutdown stops admitting commands, wakes the selector, discards pending commands, closes all owned sockets and joins the selector with a watchdog. Caller-owned sockets from refused registration are never closed by this component.

### Resource caps

Options specify maximum registrations (1..4096), command queue size (1..4096), commands/tick, ready events/tick, bytes/read (1..65536), paused slots/tick, and select timeout (1..1000 ms). Total per-registration buffers are capped at 8 MiB. These configuration maxima bound memory and per-tick work **but do not establish OS/JVM execution-time bounds**.

## Executable host verification

Run from repo root with JDK 21+:

```sh
mkdir -p build/nio-host
javac -d build/nio-host \
  src/main/java/org/attolang/runtime/SerialCarrierScheduler.java \
  src/main/java/org/attolang/runtime/BoundedNioReactor.java \
  src/test/java/BoundedNioReactorProbe.java
java -ea -cp build/nio-host BoundedNioReactorProbe
```

The loopback probe verifies real socket reads with a blocked actor and saturated mailbox (no over-admission or loss), registration-capacity refusal without ownership theft, descriptor-generation fencing across reuse, and simultaneous close with no leaked registered sockets/commands. The GitHub workflow requires 12 repetitions on Java 21 and Java 25, then checks the existing serial-carrier regression probes.

**Evidence boundaries:** Local Java 21 compilation and 30 host-only loopback repetitions were executed using an API-compatible scheduler test double while developing the reactor; those results are *not* exact-source scheduler integration evidence. The repository workflow compiles against the actual scheduler and must produce logs before a full integration pass can be claimed. Guest conformance remains NOT_EXECUTED; hard RT remains NOT_CERTIFIED.

## Follow-up gates

- Prioritize a proper generation-fenced mailbox-credit notification so resumption no longer requires select-timeout polling.
- Support framed IO, OP_WRITE, byte-credit accounting, deadlines, read/write quotas, and bounded cancellation completions.
- Implement real supervisor CONTROL priority select independently of user-language select semantics.
- Measure scheduler lease wait, reactor wakeup/read-to-admission, CONTROL notice latency and deadline misses at p99.9/p99.99 with explicit load envelope.
- Evaluate native/RTOS implementation separately for bounded interrupt latency, lock-time analysis, allocation guarantees, and WCET.

Spec: https://github.com/atto-lang/atto-spec/pull/17
Carrier pool: https://github.com/atto-lang/atto-runtime.realtime-java/pull/4
Tracking: https://github.com/atto-lang/atto-runtime.realtime-java/issues/3
