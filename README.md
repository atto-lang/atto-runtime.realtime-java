# AttoNATS

AttoLang-first, single-repository implementation of a NATS Core + JetStream-compatible broker.

**Current status:** implementation foundation. Not production ready, not yet certified protocol-compatible, and not yet compiler/guest-verified. This is not an upstream NATS distribution.

The full project — application `.atto` source, Rust-only independent test oracle, integration fixtures, tests, CI, deployment, benchmarks and architecture — lives in this repository. The compiler is a pinned upstream dependency from [ores-truffle-oreslang](https://github.com/ores-truffle-oreslang).

## Architectural rules

- All mutable broker/consumer/stream/cluster state lives inside actor `state { ... }`.
- A **non-suspending critical method** is the only mutation entrypoint; read-only snapshots do not escape borrows.
- Absolutely no `await`, blocking filesystem call or network call while a critical state loan is held.
- Actor turns own ordering; async tasks own network/disk/replication I/O; all mailboxes and in-flight tasks are bounded.
- Core publish/subscribe is transient. JetStream PubAck means the configured durability/replication commit gate has passed.
- A stream has one authoritative sequence order. Shards/partitions are separate streams with explicit mapping.
- Never acknowledge a replicated append without quorum. Reject stale terms and generations.
- Host adapters carry bytes and capabilities only; protocol and broker semantics belong in `.atto`.
- No Python. Rust-only reference tooling.
- Source-only tests are NOT evidence of compiler, guest, AOT or clustered runtime behavior.

## Repository map

`src/` — AttoLang actor-oriented implementation foundation and interfaces.
`tools/reference/` — independent Rust parser, subject, WAL and partition **test oracle**, not a broker server.
`tests/` — protocol fixtures and negative/fault acceptance requirements.
`docs/` — protocol boundaries, architecture, durability, roadmap and exact-head evidence criteria.
`deploy/` — example single-node and 3-node configuration (not publicly deployable by default).
`.github/workflows/` — runnable Rust-only checks and separately gated guest checks.

## Working sequence

1. Execute the upstream real compiler against `src/` and fix all language compatibility issues **while retaining** `state{}` and `critical`.
2. Connect an actually available raw-bytes listener/accept/read/write transport to the Atto connection actor.
3. Pass upstream NATS Go/JS/Rust client interoperability for INFO/CONNECT/PING/PONG, PUB/SUB, wildcards and queue groups.
4. Implement file-backed WAL/recovery, JetStream API services, durable consumer ACK and redelivery semantics.
5. Implement a real three-node Raft quorum, placement/shard migration and crash/chaos tests.
6. Add hardened auth/TLS/accounts, budgets, monitoring, release and stress/soak evidence.

See [docs/roadmap.md](docs/roadmap.md) for the precise acceptance gates.
