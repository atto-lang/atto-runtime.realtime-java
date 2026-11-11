# A real Atto native AOT build/release is not available from this source head.
# Deliberately fail to build rather than ship a misleading Rust-oracle container.
FROM scratch
COPY atto-nats /atto-nats
EXPOSE 4222
ENTRYPOINT ["/atto-nats"]
