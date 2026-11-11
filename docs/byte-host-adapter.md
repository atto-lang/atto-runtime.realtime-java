# Byte-only TCP transport adapter (not a NATS server)

## Why

The pinned Java compiler's documented TCP path has no verified guest-side
raw-byte server accept/read/write capability in this project. We do **not**
pretend to expose `io.listen` or silently switch the broker to Rust.

`tools/byte-transport` implements the smallest host primitive that can be
tested now: **a loopback-only TCP listener** whose session worker starts an
explicit guest executable with scrubbed environment, forwards raw socket bytes
to guest stdin and guest stdout back to the socket. It does not parse INFO,
CONNECT, PUB, SUB, MSG, queue groups, authentication, JetStream or Raft.
No Rust broker or protocol service runs here. This is analogous to a stdio
transport capability, not to the AttoNATS application.

## Real test and evidence labels

```sh
cargo fmt --manifest-path tools/byte-transport/Cargo.toml --all -- --check
cargo test --manifest-path tools/byte-transport/Cargo.toml --all-targets --locked
cargo clippy --manifest-path tools/byte-transport/Cargo.toml --all-targets --locked -- -D warnings
```

These tests open genuine TCP sockets, spawn a separately compiled Rust-only
binary byte-echo fixture, send 65 KiB of arbitrary binary data, and verify
exact unmodified transfer. **The fixture is NOT an Atto compiler, guest or
NATS broker.** Do not count the test as Atto guest acceptance or Core
NATS interop.

## Launch contract

After building an actual Atto guest program that owns Core NATS session
logic and uses stdin/stdout for binary wire bytes:

```sh
cargo run --manifest-path tools/byte-transport/Cargo.toml --locked -- \
  127.0.0.1:4222 /ABSOLUTE/PATH/TO/PINNED/ATTO-GUEST \
  EXPLICIT_GUEST_BINARY_SHA256 EXACT_COMPILER_SOURCE_COMMIT_SHA \
  [guest-program-arguments...]
```

The adapter will refuse non-loopback listeners, missing/invalid SHA pins,
symlinked guest binaries, and over-capacity sessions. Guest subprocesses
are started without inherited environment variables, so secrets/tokens
cannot be forwarded unintentionally. Sessions enforce a bounded lifetime
and a maximum of 16 concurrently admitted subprocesses in the CLI.
Each session gets its own subprocess: **intentionally a correctness bridge
only, not efficient production actor multiplexing**.

The runner executable is presently **absent**. Passing `sha256sum` checks
only that the supplied executable matches the digest, not that it was
built from the asserted compiler source. An independent provenance
receipt is required, and a malicious/mock executable can still fake NATS
bytes. The `tools/guest-gate` execution marker is a separate evidence layer.

## Current blocker

The actual Atto guest must (1) compile actor `state{}` + `critical`,
(2) expose an unbuffered binary stdin/stdout capability without host
protocol parsing, (3) implement the NATS frame parser, CONNECT auth and
`SubjectRouterActor` subscription/delivery fanout, and (4) handle a
session's lifecycle with bounded mailboxes and async I/O.

Until a **real Atto process** drives the client in
`tools/tcp-interop`, there is no end-to-end AttoNATS broker.
Do not open public TCP ports, advertise compatibility or enable
production JetStream. Even after this proof, a single Atto process with
actor-owned sessions and per-tenant isolation is the production target:
replace the per-session subprocess transport with an efficient
runtime-native byte capability.
