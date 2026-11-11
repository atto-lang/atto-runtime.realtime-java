# Contributor/agent rules for AttoNATS

1. Put domain logic in `.atto`, not Java/Rust/Go; `tools/reference` is a **test oracle only**.
2. **Assume** `state { }` is the selected language contract. Use it in actual actor code. Do not silently rewrite state to unguarded instance fields to make an old compiler pass.
3. Mutation must occur only from `critical` methods through `self.handle_state` loans. No critical section may suspend or call disk/network; split reserve/await/commit with version/epoch checking.
4. Enforce exactly one owner and explicit `rt borrow/share/take/copy` transfers. No raw pointers across actor arenas; loan/epoch/generation fencing.
5. All non-void functions contain explicit `return` statements (except braceless lambdas).
6. All async I/O uses awaitable API names and explicit bounded cancellation semantics. Do not block scheduler carriers.
7. No Python, no code-generating Python or Python workflows.
8. Reject zero-step CI as runner admission failure, not test success. Pin compiler/source/tree identity.
9. No public listener default; bind loopback until authentication, TLS and tenant limits are verified.
10. Source code, deployment, testing and docs all remain in this ONE repository; external compiler is a build dependency, not a second clone implementation.
