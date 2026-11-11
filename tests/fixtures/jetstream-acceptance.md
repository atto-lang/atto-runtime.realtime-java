# JetStream external interoperability fixtures (not yet executed)

- $JS.API.STREAM.CREATE.<name>: full response JSON, stream config, errors.
- Publish Nats-Msg-Id, verify duplicate window and restart behavior.
- Reopen files and prove committed per-stream monotonic sequence.
- Pull consumer fetch/ACK/NAK/TERM, timeout redelivery and durable cursor.
- Three-nodes: stop leader, elect new leader, reject writes without quorum.
- Differentials versus a version-pinned, official nats-server.
- Binary payloads, malformed headers, auth fail-closed and session cleanup.
