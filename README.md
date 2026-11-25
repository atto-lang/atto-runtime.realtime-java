> **AI agent instructions (organization-wide):** Before working on code, issues, or pull requests, read and follow the [organization `AGENTS.md`](https://github.com/atto-lang/.github/blob/main/AGENTS.md), including its **merge-first stealth-development policy**. Honor any repository-local `AGENTS.md`, explicit holds, and GitHub-enforced rules.

# atto-runtime.realtime-java

Low-latency Java runtime research for AttoLang actors, scheduling, and memory management.

## Naming migration and language authority

This repository is maintained at [atto-lang/atto-runtime.realtime-java](https://github.com/atto-lang/atto-runtime.realtime-java); use `https://github.com/atto-lang/atto-runtime.realtime-java.git` for Git remotes and canonical `.atto` source examples. The normative language contract is [atto-spec](https://github.com/atto-lang/atto-spec), with executable guest evidence tracked by [atto-platform-conformance](https://github.com/atto-lang/atto-platform-conformance). Follow current ownership, borrowing, effects, actor, and syntax rules there; do not infer implemented features from a README or unexecuted CI.

## Scope

This repository is the AttoLang ecosystem home for:

- Latency-oriented actor scheduling, memory/arena strategies, and reproducible real-time benchmarks.
- Compatibility with the [canonical language specification](https://github.com/atto-lang/atto-spec) where applicable.
- Evidence-based tests, benchmark results, and cross-backend conformance reporting.

## Architecture and compatibility

AttoLang is designed around actors, explicit ownership, and portable concurrency semantics. Backend-specific optimizations must not silently change the shared language contract. Runtime capabilities, target restrictions, and unsupported features should be documented explicitly.

## Building and testing

Consult the checked-in source tree and CI workflows for verified commands and supported targets. Add target-specific build instructions and exact-head execution results as they become available; do not treat a passing host-unit test as proof of guest-language conformance.

## Project status

This README describes the repository's intended responsibilities. Feature support and release readiness must be established from implementation, tests, and CI evidence rather than this description alone.

## Related repositories

- [AttoLang organization](https://github.com/atto-lang)
- [Language specification](https://github.com/atto-lang/atto-spec)
- [Platform conformance](https://github.com/atto-lang/atto-platform-conformance)
- [Commercial infrastructure](https://github.com/atto-co)

## Repository ownership and contribution routing

**Belongs here:** Latency-optimized Java actor scheduler, deterministic queues, native/offheap arena research and benchmark characterization.

**Belongs elsewhere:** Full Java compiler frontend and GraalVM AOT implementation live in atto-source.java; common actor semantics in atto-spec.

**Security and portability boundary:** State explicitly which results are host-only benchmarks versus compiled AttoLang guest execution; constrain pause times, starvation, admission and actor isolation.

AttoLang is the canonical project name; new source, examples and documentation use `.atto` and the `atto-lang` repository organization. This README defines scope, not feature readiness.

**Acceptance:** name exact source SHA/tree, runtime/toolchain, positive and negative checks and execution receipt before claiming support. Host-only tests and empty CI steps do not prove guest execution.

## Current portable admission layer

- `tools/rt_bytecode_audit.py`: dependency-free, **fail-closed syntactic admission filter** for a deliberately restricted primitive-only Java bytecode entry point.
- `tests/`: Javac-based acceptance/rejection regression suite with no proprietary VM SDK requirement.
- `docs/`: runtime contract, memory-lifetime rules, proof gates, and VM qualification guidance.

The bytecode auditor uses a strict opcode allowlist, classfile 52.0 pinning, exact attribute decoding, forward-only CFG boundary validation, transitive static call counting, and SHA-256 evidence binding. It rejects hidden class initializers, fields, unusual inheritance, malformed bytecode, integer division/remainder, allocation, object references, arrays, monitors, dynamic dispatch, backward branches, and unverified external calls in the admitted subset.

```sh
python3 -m unittest discover -s tests -v
javac --release 8 -g:none -d /tmp Example.java
python3 tools/rt_bytecode_audit.py /tmp/Example.class --entry tick \
  --max-bytecodes 1000 --max-call-depth 16
```

A PASS is **not** a hard-real-time, no-heap, WCET, or GC-independence certificate. The reported instruction bound is not time. This filter is one necessary admission gate only.

## Hard-real-time goal

Support a strict Java hard-real-time profile without weakening the ordinary GraalVM/JVM backend. The hard-RT profile is a separate admission domain. Finding RTSJ classes, choosing a low-pause collector, or compiling only the host runtime ahead of time is never sufficient evidence.

A qualified critical domain must establish all of these properties together:

- full guest AOT for the reachable critical call graph;
- runtime code generation disabled in the critical domain;
- compiler evidence for closed-world, bounded, allocation-free critical code;
- collector-independent critical execution with a documented bound;
- actor state in qualified scoped/native non-tracing regions;
- deterministic region reclamation and bounded cleanup;
- critical memory preallocated, prefaulted, and resident/locked where the target requires it;
- bounded scheduler dispatch and bounded priority inversion;
- isolation from general-purpose or untrusted JIT workers;
- blocking I/O excluded and every reachable FFI/native bridge qualified;
- timing evidence for WCET, blocking, interference, and release jitter;
- a deployment scheduler policy capable of honoring declared deadlines.

## RTSJ and vendor runtimes

RTSJ 2 moved scoped-memory APIs into `javax.realtime.memory` and deprecated legacy APIs including `NoHeapRealtimeThread`. AttoLang therefore does not use presence of that deprecated class as the definition of real-time safety.

Different qualified runtimes may establish collector independence differently. A runtime may use scoped/no-heap execution, or it may provide bounded collector interference/preemption latency that is included in timing qualification.

The adapter layer separates four evidence classes:

1. `RtsjPresence`: reflective API discovery only.
2. `CompilerArtifactEvidence`: proof about the exact AttoLang artifact.
3. `VmQualification`: VM, memory, scheduler, AOT, and deployment guarantees.
4. `HardRealtimeAdmission`: fail-closed composition of all evidence plus the response-time budget.

`RealtimeVmAdapter` is the vendor SPI and providers are discovered with `ServiceLoader`. There is deliberately no generic JVM hard-RT provider: no qualified adapter means no AttoLang hard-RT qualification.

## Compiler artifact evidence

A qualified RT VM cannot bless arbitrary Java bytecode. The Java compiler backend must emit an admitted critical subset and audit the resulting classfile before it becomes a candidate RT artifact. Evidence tracks the exact classfile SHA-256, source/ownership admission, closed-world calls, bounded control flow, post-lowering audit, and the absence of allocation bytecodes, dynamic dispatch, and monitor operations.

Deployment must bind the loaded/AOT-compiled artifact back to the same digest before trusting that evidence.

## Actor arenas and GC

Per-actor arenas are central to AttoLang, but backing storage matters. An ordinary Java object graph remains JVM-managed even when AttoLang logically calls it an actor arena. Direct `ByteBuffer` can move payload bytes off heap, but wrappers and cleaners remain managed. Those approaches can reduce GC pressure but do not prove zero-GC critical execution.

The hard-RT Java target therefore requires either:

1. a qualified RTSJ scoped-memory implementation whose semantics and collector behavior meet the declared timing bound; or
2. compiler lowering of guest actor state into explicit native regions with deterministic lifetime and no traced references escaping into them.

The general Java/GraalVM runtime may continue to use managed objects outside the critical domain.

## AOT terminology

AttoLang distinguishes:

- **host AOT**: the Java host/runtime was compiled ahead of time;
- **guest AOT**: AttoLang guest code was compiled ahead of time;
- **strict full guest AOT**: every reachable critical guest function executes without an interpreter, runtime JIT, or runtime code generation.

Only strict full guest AOT satisfies the hard-RT compilation requirement.

## Platform work

- JamaicaVM: bind a version-pinned supported SDK/runtime/AOT compiler and licensed CI runner, then implement and qualify the RTSJ scoped-memory/no-heap adapter. Do not mix legacy `javax.realtime.LTMemory` assumptions with RTSJ 2 `javax.realtime.memory.LTMemory`.
- PTC Perc: establish supported Java/classfile/API versions and document actual memory, AOT, and scheduling guarantees before claiming qualification.
- Standard JVM/GraalVM: soft-RT baseline unless a separately qualified deployment proves the stricter profile.

## Safety, security, and reproducibility

Hard-RT admission is target-bound to a VM build, AOT toolchain, RTOS/OS, CPU, scheduling configuration, and certified library set. No proprietary SDK, license, CI credential, or source mirroring is required by the portable tests. Production qualification additionally requires backend inspection, transitive effects and memory-region proof, platform-specific WCET/response-time analysis, and on-target stress validation.

The canonical backend-neutral contract lives in `atto-common-source/contracts/v1/realtime-execution.v1.schema.json`.

## Guarded migration and ownership

The current canonical repository is `atto-lang/atto-runtime.realtime-java`. Its legacy counterpart is archived; do not infer a numeric-ID-preserving transfer or authorize any new transfer. Preserve existing Maven coordinates, Java package names and historical receipts until a separately reviewed compatibility migration. Portable core/std implementations belong in the compiler-owned libraries; commercial hosted services belong under separately governed ownership.

Any future transfer must have named source/target administrators, independent security approval, exact source commit and tree, access/rulesets, Actions, secrets, releases, apps, webhooks, consumers and rollback reviewed. Never mirror private source without separate authorization. Verify identity and control parity before cutover; zero-step Actions are NOT_EXECUTED. This documentation authorizes no transfer.
