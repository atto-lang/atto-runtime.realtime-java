# Oreslang real-time Java runtime (incubating)

This repository houses the **JVM-specific real-time qualification layer** for Oreslang. It is **not** a replacement for `oreslang-source.java` or `oreslang-source.llvm`. Core ownership, effects, resource budgets, and the backend-neutral real-time contract belong in the shared Oreslang language/compiler contracts; this runtime adapts those contracts to particular JVMs and target systems.

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

RTSJ 2 moved scoped-memory APIs into `javax.realtime.memory` and deprecated legacy APIs including `NoHeapRealtimeThread`. Oreslang therefore does not use presence of that deprecated class as the definition of real-time safety.

Different qualified runtimes may establish collector independence differently. A runtime may use scoped/no-heap execution, or it may provide bounded collector interference/preemption latency that is included in timing qualification.

The adapter layer separates four evidence classes:

1. `RtsjPresence`: reflective API discovery only.
2. `CompilerArtifactEvidence`: proof about the exact Oreslang artifact.
3. `VmQualification`: VM, memory, scheduler, AOT, and deployment guarantees.
4. `HardRealtimeAdmission`: fail-closed composition of all evidence plus the response-time budget.

`RealtimeVmAdapter` is the vendor SPI and providers are discovered with `ServiceLoader`. There is deliberately no generic JVM hard-RT provider: no qualified adapter means no Oreslang hard-RT qualification.

## Compiler artifact evidence

A qualified RT VM cannot bless arbitrary Java bytecode. The Java compiler backend must emit an admitted critical subset and audit the resulting classfile before it becomes a candidate RT artifact. Evidence tracks the exact classfile SHA-256, source/ownership admission, closed-world calls, bounded control flow, post-lowering audit, and the absence of allocation bytecodes, dynamic dispatch, and monitor operations.

Deployment must bind the loaded/AOT-compiled artifact back to the same digest before trusting that evidence.

## Actor arenas and GC

Per-actor arenas are central to Oreslang, but backing storage matters. An ordinary Java object graph remains JVM-managed even when Oreslang logically calls it an actor arena. Direct `ByteBuffer` can move payload bytes off heap, but wrappers and cleaners remain managed. Those approaches can reduce GC pressure but do not prove zero-GC critical execution.

The hard-RT Java target therefore requires either:

1. a qualified RTSJ scoped-memory implementation whose semantics and collector behavior meet the declared timing bound; or
2. compiler lowering of guest actor state into explicit native regions with deterministic lifetime and no traced references escaping into them.

The general Java/GraalVM runtime may continue to use managed objects outside the critical domain.

## AOT terminology

Oreslang distinguishes:

- **host AOT**: the Java host/runtime was compiled ahead of time;
- **guest AOT**: Oreslang guest code was compiled ahead of time;
- **strict full guest AOT**: every reachable critical guest function executes without an interpreter, runtime JIT, or runtime code generation.

Only strict full guest AOT satisfies the hard-RT compilation requirement.

## Platform work

- JamaicaVM: bind a version-pinned supported SDK/runtime/AOT compiler and licensed CI runner, then implement and qualify the RTSJ scoped-memory/no-heap adapter. Do not mix legacy `javax.realtime.LTMemory` assumptions with RTSJ 2 `javax.realtime.memory.LTMemory`.
- PTC Perc: establish supported Java/classfile/API versions and document actual memory, AOT, and scheduling guarantees before claiming qualification.
- Standard JVM/GraalVM: soft-RT baseline unless a separately qualified deployment proves the stricter profile.

## Safety, security, and reproducibility

Hard-RT admission is target-bound to a VM build, AOT toolchain, RTOS/OS, CPU, scheduling configuration, and certified library set. No proprietary SDK, license, CI credential, or source mirroring is required by the portable tests. Production qualification additionally requires backend inspection, transitive effects and memory-region proof, platform-specific WCET/response-time analysis, and on-target stress validation.

The canonical backend-neutral contract lives in `oreslang-common-source/contracts/v1/realtime-execution.v1.schema.json`.

## Repository ownership and migration (2026-11-08)

**AttoLang** is the public language name; the current repository is still `ores-truffle-oreslang/oreslang-runtime.realtime-java` (legacy namespace). Preserve existing `oreslang-*` identifiers, old commands and `.ores` compatibility until migration and feature support are independently verified.

- **Belongs here:** Java real-time runtime integration, scheduler/host primitives and timing validation.
- **Does not belong here:** portable core/std algorithms duplicated as Java-only code or hosted commercial service.
- **Ownership:** [`atto-lang`](https://github.com/atto-lang) owns language, compiler, tooling, portable core/std, conformance and developer ecosystem; [`atto-co`](https://github.com/atto-co) owns separately governed hosted runtime, deployment, identity, billing and cloud operations. An existing similarly named `atto-*` repo does not automatically contain this repo's code or retain its numeric identity.
- **Proposed first destination:** `atto-lang/oreslang-runtime.realtime-java` via an approved *GitHub repository transfer* preserving numeric ID/history/name. The existing plan authorizes **no** direct legacy-org transfer to `atto-co`; future product spinouts need their own approval.

**Cutover instructions:** coordinate in [migration issue #26](https://github.com/ores-truffle-oreslang/.github/issues/26) and consult the private [runbook draft #25](https://github.com/ores-truffle-oreslang/.github/pull/25). Require named source/target admins and independent security reviewer, exact head SHA/tree, target protection/rulesets, permissions, Actions and actual executed steps, secrets, releases, Pages, packages, apps, webhooks, open PRs, consumers and rollback owner before any transfer; never mirror private code without separate authorization. Verify unchanged GitHub ID/tree and control parity afterward, update clients separately, and execute exact-head tests. Zero-step Actions are `NOT_EXECUTED`; host-only checks are not compiled-guest evidence. No forced language/CLI rename or production capability claims.
