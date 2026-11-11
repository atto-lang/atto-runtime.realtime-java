# Storage and consensus durability contract

## On-disk file v0

This project proposes `u32_le(payload_bytes) | u32_le(crc32(payload)) | bytes`.
The independent Rust oracle implements this FRAME FORMAT only. **The Atto
WAL writer does not yet persist these frames**.

A real WAL adds committed sequence and term metadata, checkpoints, immutable
segment indexes, fsync policies, manifest generation, exclusive process lease,
directory fsync on rename and explicit recovery rules.

Accept an incomplete final tail as torn only when it is unambiguously the final
record; reject corrupt complete records anywhere, including the last complete
record. Never silently skip corrupted records to fabricate a contiguous log.
No PubAck before a real storage barrier for file mode.

## Raft

For replicas=3 require at least 2 matching durable log acknowledgments before
declaring a new record committed; for 5 require 3. No leaderless writes.
Record current term, voted-for, commit index, placement epoch and membership in
durable metadata. Followers reject stale term/epoch. Newly elected leaders
resolve outstanding uncommitted reservations without inventing ACK successes.

## Crash tests

Kill after reserved sequence, partial header, partial payload, complete WAL
write before fsync, post-fsync before PubAck, after quorum before PubAck,
during checkpoint, leader split and stale old-leader comeback. A consumer
must tolerate re-delivery after uncertain ACK without losing committed records.
