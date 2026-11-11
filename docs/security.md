# Security policy / default limits

Not suitable for untrusted/public exposure yet. Bind TCP to 127.0.0.1 only
until TLS/auth/accounts/permission checking is proven. Never expose the demo
cluster's service ports directly to the public Internet.

Target limits: control line <=4 KiB, payload <=1 MiB initially, connection
partial-frame buffer <=2 MiB, subscriptions <=10,000 per tenant, bounded
outbound connection queues, bounded writer queues and bounded outstanding
consumer fetches. These are design targets, not enforced by the current guest.

Reject malformed lengths, integer overflow, wildcard publishes, invalid
UTF-8 in command headers, embedded CR/LF subject injection, symlinked
storage roots, duplicate storage lease and unsafe WAL rotation.
Do not import or export native descriptors across untrusted actors.
TLS certs and authorization secrets remain outside guest message payloads.
No operator-installed Python, external unpinned binaries or cross-org
repository tokens in CI. Verify exact source tree identities when mirroring.
