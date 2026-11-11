#!/usr/bin/env bash
set -euo pipefail

# No guest credit unless an actual pinned compiler binary executes .atto code.
: "${ATTO_RUNNER:?path to prebuilt, independently verified Atto runner}"
: "${ATTO_COMPILER_SHA:?exact 40-hex upstream compiler source commit}"
: "${ATTO_RUNNER_SHA256:?exact 64-hex SHA-256 of the executable runner}"

cd "$(dirname "$0")/.."
cargo run --manifest-path tools/guest-gate/Cargo.toml --locked -- \
  "$ATTO_RUNNER" tests/guest/actor_state_critical.atto \
  "$ATTO_COMPILER_SHA" "$ATTO_RUNNER_SHA256" \
  "ATTO_GUEST_EXECUTED:state-critical"
