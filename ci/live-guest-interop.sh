#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# This is a FAIL-CLOSED manual acceptance gate, not part of the Rust-only
# source oracle. No values are inferred or invented from successful CI.
: "${ATTO_NATS_GUEST_BINARY:?Absolute path to REAL compiled Atto NATS guest}"
: "${ATTO_NATS_GUEST_SHA256:?64-digit actual guest binary sha256}"
: "${ATTO_NATS_COMPILER_SHA:?40-digit verified compiler source sha}"
: "${ATTO_NATS_SOURCE_SHA:?40-digit verified Atto application source sha}"
: "${ATTO_NATS_PROVENANCE_RECEIPT:?independently checked provenance receipt}"

cargo test --manifest-path tools/byte-transport/Cargo.toml --locked \
  --features live-atto-guest --test live_atto_guest -- --nocapture
