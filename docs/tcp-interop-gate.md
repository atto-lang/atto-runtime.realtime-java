# Core NATS TCP interop gate — client only

## Scope

`tools/tcp-interop` is a **Rust TCP protocol acceptance client** with
bounded control lines, timeouts, exact binary message lengths and real socket
reads/writes. It verifies the externally observable NATS Core sequence:

1. Connect to an explicit `IP:port`.
2. Read `INFO {...}\r\n`.
3. Send `CONNECT {...}\r\n`, `PING\r\n`; require `PONG\r\n`.
4. `SUB` a collision-resistant fresh subject; `PING` and verify flush.
5. Publish six binary bytes, including NUL, CR, LF and 0xFF.
6. Receive the same bytes as a server `MSG` on exactly the requested
   subject and SID, with exact length and CRLF terminator.
7. `UNSUB` to clean up.

## Test commands

```sh
cargo fmt --manifest-path tools/tcp-interop/Cargo.toml --all -- --check
cargo test --manifest-path tools/tcp-interop/Cargo.toml --all-targets --locked
cargo clippy --manifest-path tools/tcp-interop/Cargo.toml --all-targets --locked -- -D warnings
```

Those run Rust-only fixtures, **not an Atto broker**.

Once a real guest-verified AttoNATS TCP server has been started and its
executable SHA-256/source commit verified, run:

```sh
cargo run --manifest-path tools/tcp-interop/Cargo.toml --locked -- 127.0.0.1:4222
```

The command fails if no real TCP listener is reachable or protocol bytes
differ. Even a successful probe is **not Atto provenance evidence**: running
it against stock `nats-server` would also succeed, so record the server
PID, process binary path and SHA-256, Atto source SHA, compiler head and
guest stdout evidence independently.

## Next implementation blockers

- Implement the Atto-owned raw TCP socket capability `listen`, `accept`,
  `read_bytes`, `write_bytes`, cancellation and graceful close.
- Build `NatsListenerActor` session lifecycle and `ConnectionActor`
  async ingress/egress tasks, tied to actor generation and bounded buffers.
- Move the already Rust-tested protocol oracle semantics into the Atto
  connection parser, including true binary framing and fail-closed CONNECT
  authentication.
- Add an actual official `nats` Rust/Go client interop test, independent
  of this raw protocol harness, and verify multiple client fan-out,
  queue-group routing and request/reply.
- Execute under a pinned real Atto guest runtime/NativeAOT binary and
  preserve session-level memory and backpressure invariants.

**Never add a Rust broker server to satisfy this gate.** The test fixture
starts a tiny Rust socket responder only under `#[cfg(test)]` so the
acceptance client itself is tested without asserting broker compatibility.
