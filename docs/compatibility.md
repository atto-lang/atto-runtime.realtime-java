# NATS / JetStream compatibility contract

Reference: https://docs.nats.io/reference/protocols/client and
https://docs.nats.io/concepts/jetstream .

Core client protocol: INFO/CONNECT/PING/PONG/SUB/UNSUB/PUB/MSG/
HPUB/HMSG/+OK/-ERR. Operations are case-insensitive. CRLF separates commands;
payload size is measured in bytes, not characters. A read may split any frame.
Max control line, max payload, max subscriptions and outbound queues are bounded.

Subject semantics: tokens separated by dot, `*` consumes exactly one token,
`>` is terminal and consumes one or more tokens. Queue groups choose one member
for each matching queue subscription, while ordinary subscribers all receive a
copy. Reconnect/session exit removes ephemeral subscriptions.

JetStream: create stream via NATS request/reply API, persist matching subjects
with per-stream sequence, assign PubAck after required durable commit, implement
durable consumers, ACK/NAK/TERM/in-progress, redelivery, limits/interest/workqueue
retention, dedup window and stale consumer/leader fencing.

Differential tests MUST drive a real pinned `nats-server` and AttoNATS with
identical client fixtures and compare errors, observable responses, sequence
ranges, restart behavior, retention, fanout and delivery guarantees.
Never say "compatible" before the corresponding end-to-end fixture passes.
