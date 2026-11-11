# Test layers

1. `cargo test --manifest-path tools/reference/Cargo.toml` — executable
   Rust-only independent oracle. **NOT Atto guest execution**.
2. `src/main.atto` and each .atto module — compiler and guest admission
   required with a pinned compiler and non-zero executed steps.
3. Real TCP protocol integration — unmodified official NATS clients and CLI.
4. JetStream differential tests — pinned reference nats-server with restart,
   ACK/replay/dedup/retention behavior.
5. Cluster chaos tests — partition/quorum/failover/rollbacks.
6. Native AOT — separate compile/run receipts, and no claims from host canaries.

All required tests must publish machine-readable receipts including exact SHA,
executed step counts, command, exit code and scope; source-only checks do not
satisfy any guest gate.
