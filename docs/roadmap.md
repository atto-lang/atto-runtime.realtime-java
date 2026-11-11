# Eight execution gates

Status labels: `DESIGNED`, `SOURCE_PRESENT`, `COMPILED`,
`GUEST_EXECUTED`, `INTEROP_VERIFIED`, `PRODUCTION_VERIFIED`.
A source or Rust oracle test pass is NOT a compiler or guest pass.

| Milestone | Current | Required proof |
| --- | --- | --- |
| P1 Atto bootstrap/actor state | SOURCE_PRESENT | Pin real upstream compiler SHA; compile and run state{} + critical guest |
| P2 Core NATS protocol | SOURCE_PRESENT | Real byte TCP accept and standard NATS clients connect, PING, PUB/SUB, HPUB |
| P3 routing/queue groups | SOURCE_PRESENT | Wildcard trie, multiple subs, queue load balance, request/reply interop |
| P4 single-node JetStream | SOURCE_PRESENT | Stream API, real WAL, fsync, restart and torn-write recovery |
| P5 durable consumer | SOURCE_PRESENT | Pull/push, ACK, NAK, timeout, redelivery, durable cursor restart |
| P6 Raft cluster | SOURCE_PRESENT | 3-node quorum, leader failure, divergence repair, fencing |
| P7 shards and security | SOURCE_PRESENT | stable mapping, no split ordering, auth, TLS, quotas, fault isolation |
| P8 release | SOURCE_PRESENT | AOT, Docker, k8s, soak, perf, chaos, monitored release |

Only P1 actor source smoke exists. P2–P8 have architectural/source footholds
but are NOT feature-complete implementations. They must remain open until
real execution and client interoperability receipts exist.

Evidence includes exact source head, compiler head, job/attempt ID, steps>0,
commands, exit codes, guest executed count and artifacts. Zero-step Actions
represent admission failure, not passing or failing guest tests.

Do not merge production claims based on source-only fixtures or the Rust oracle.
