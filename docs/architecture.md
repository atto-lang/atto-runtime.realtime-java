# AttoNATS architecture

## Ownership design

`BrokerSupervisor` owns node lifecycle; `ConnectionActor` owns a TCP client
session; `SubjectRouterActor` owns a portion of subscription indexes;
`StreamActor` serializes stream log order; `ConsumerActor` owns ACK and
delivery cursors; `WalWriterActor` owns append ordering; `RaftGroupActor`
owns consensus state. All mutable actor fields MUST be inside `state{}`
and all modifications through critical methods / `handle_state`.

Supervisors must not gain direct raw actor-state pointers. Send immutable
envelopes, stable identifiers and generation-checked handles through bounded
mailboxes. Actor heaps and stacks remain isolated even when code is shared.
Never carry protected mutable state loans through await. Critical sections
are for short state transitions and must not do TCP, disk or consensus I/O.

## A publish transaction

1. A connection reader task validates raw binary framing and authentication.
2. A routed mailbox message reaches the owning subject/router shard.
3. Core transient fanout is delivered only to matching live subscribers, subject
   to bounded output queues and NATS at-most-once semantics.
4. For each matching JetStream stream, reserve a contiguous ordering position
   in a critical method. Write asynchronously to WAL and replicate to followers.
5. Persist/commit with required durability and Raft quorum. Re-enter critical
   scope and check stream incarnation, leader term and expected sequence.
6. Reply with JetStream PubAck ONLY after the configured durability barrier.
7. Consumer actors observe committed messages and schedule bounded deliveries.

## Stream ordering versus partitioning

A stream has one total order. Sharding the subject trie or scheduler lanes
does NOT imply stream partitions. Partitioning is multiple streams with stable
subject-to-stream mapping; consistent reconfiguration requires an explicit
epoch/placement migration protocol. A shared consumer can scale work across
many clients without partitioned streams.

## Transport

Upstream currently documents client `network.connect` and line-based
`read_line`, which are insufficient by themselves for NATS byte-length frames
and server accept. Implement or bind a minimal capability-scoped raw-byte server
adapter; no Atto broker decisions may be hidden in the adapter. Do not claim the
Atto TCP server works until a real client interoperates.

## Safety

Host input: reject oversized control line, payload length overflow, truncated
CRLF, invalid subject tokens, auth bypass and excessive subscriptions. Put
limits on connection/actor mailboxes, partial frame buffers, disk writes, WAL
recovery, ACK timers and outbound socket buffers. Fail closed on leader fencing
and unavailable quorum. Cancel and physically quiesce tasks before reclaiming
memory or socket capabilities.
