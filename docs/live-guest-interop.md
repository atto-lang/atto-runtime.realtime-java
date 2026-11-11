# Live Atto NATS executable acceptance gate

This is a **separate opt-in integration gate** requiring an independently
built, genuine AttoLang executable implementing the NATS Core byte protocol.
Ordinary Rust oracle tests do not run or pass this gate.

## Run on a trusted Linux CI runner

Supply a real native guest binary, its independently measured SHA-256,
an exact upstream compiler commit, an exact AttoNATS application commit,
and an externally checked provenance receipt containing those identities:

```sh
ATTO_NATS_GUEST_BINARY=/absolute/path/to/actual-atto-guest \
ATTO_NATS_GUEST_SHA256=... \
ATTO_NATS_COMPILER_SHA=... \
ATTO_NATS_SOURCE_SHA=... \
ATTO_NATS_PROVENANCE_RECEIPT=/absolute/path/to/provenance.txt \
bash ci/live-guest-interop.sh
```

- A missing binary, pin or receipt is an immediate **failure**, not a skip
  or not-executed success. The script is not included in the reference CI.
- The test launches the loopback-only, byte-blind transport, then the supplied
  Atto executable, and uses the *existing Rust NATS TCP client* to verify
  INFO/CONNECT/PING/PONG/SUB/PUB/MSG/UNSUB and binary payload fidelity.
- A plain-text receipt is **not signature verification**. A reviewer must
  independently verify source/tree identity, builder/publisher authenticity,
  CI job attempt, runner environment, binary digest and attached provenance.
- A synthetic Rust echo fixture or stock `nats-server` could satisfy the
  wire behavior if deliberately passed as the guest; such a result is
  **NOT acceptable Atto evidence**. Source identity and compiler execution
  must be independently established.
- The adapter uses Linux `/usr/bin/sha256sum` rather than a search-PATH
  binary. On a non-Linux host, implement a verified trusted SHA-256 provider
  instead of silently selecting an executable through untrusted PATH.

## Current runtime blocker

Atto has not yet produced a verified actor `state{}` / `critical` guest
binary that reads/writes raw byte streams, parses NATS frames, authenticates
CONNECT, and delivers subscriptions. `ConnectionActor` now includes
generation-fenced publication reserve/finish/abort transitions, but the
input byte parser and actor mailbox routing are **not wired**.

The first proof is a **real** Atto guest process reaching the live socket
test. Then test two concurrent connections, queue groups, wildcard fanout,
request/reply, limits, disconnects and auth revocation. JetStream awaits
durable WAL/restart/leader tests after Core NATS works.
