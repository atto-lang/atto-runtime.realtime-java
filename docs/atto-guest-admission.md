# Pinned Atto compiler and guest admission

Current suggested upstream source commit:
`ores-truffle-oreslang/oreslang-source.java@6d8eb70048eafc9a84c5aebe9c1bb06104f16e76`.
This is a **source identity candidate**, not a compiled binary or evidence of
`state{}` / `critical` language support.

## Required executable proof

1. Build a real Atto runner from a verified **exact Git source commit**.
   Record the compiler source SHA and runnable binary's SHA-256.
2. Provide the runner as an executable file on the runner host. The connection
   must be properly authorized; **never use another organization's GITHUB_TOKEN
   to fetch a private compiler repository**. A publisher may deliver an
   independently verified compiler artifact inside an approved CI workflow.
3. Set `ATTO_RUNNER`, `ATTO_COMPILER_SHA`, `ATTO_RUNNER_SHA256`.
4. Execute `bash ci/guest-admission.sh` on a local or funded job with real steps.
5. Record stdout marker, exit code, source commit, compiler commit, binary
   digest, exact job id and attempt. Source acceptance is not equivalent to a
   completed actor mailbox turn.

The Rust `tools/guest-gate` crate executes the provided runner using
`std::process::Command`, not a shell. It requires an exact compiler source
commit and the literal binary SHA-256, and rejects a successful compiler
invocation that doesn't emit a full-line guest stdout marker. This gate
does NOT prove the runner was built from the claimed source commit; that
requires an independent release/provenance receipt. A malicious or mock runner
could emit the marker; the gate is only one evidence layer.

## Negative cases required for production

- Reject direct state mutation without a `critical` gate.
- Reject mutable state loans held across `await`.
- Reject direct calls to `external mtd init` and `external mtd receive`.
- Reject cross-actor mutable aliases and generation-stale capability reuse.
- Verify actor turn completion via request/reply or a supervised join (the
  current smoke marker only demonstrates execution of guest `main`).
- Verify that stop and cancellation quiesce outstanding tasks before reclaim.

Keep these tests in `tests/guest/negative/` with explicit expected error
diagnostics, and implement a negative-test driver after the compiler has
stabilized output classifications. Until then, do **not** misclassify a
compile failure due to unsupported `state{}` syntax as a negative pass.

## Evidence separation

`cargo test` in `tools/guest-gate` proves only the Rust admission
validator, **not Atto execution**. No ordinary PR checks claim guest
execution if the prebuilt pinned Atto runner is absent.
