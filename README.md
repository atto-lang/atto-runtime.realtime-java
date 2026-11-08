# atto-runtime.realtime-java

Low-latency Java runtime research for AttoLang actors, scheduling, and memory management.

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

AttoLang is the public name; preserve existing Oreslang/.ores compatibility and use canonical .atto for new portable library source. This README defines scope, not feature readiness.

**Acceptance:** name exact source SHA/tree, runtime/toolchain, positive and negative checks and execution receipt before claiming support. Host-only tests and empty CI steps do not prove guest execution.
