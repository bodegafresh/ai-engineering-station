# H02-D2C2f.19 — real-service publication/revocation ordering

Baseline `589eedb01cf395da9c704d43691e8fc08606c114` (.18 merged).

## Acceptance evidence

- **Publish-first:** an independent JVM executes the actual `WorkspaceDocumentationService` with the production policy gate held at `AFTER_FILE_JOURNAL`; an operator revocation is attempted concurrently. Its acknowledgement must wait until publication releases the gate. Verify final file digest against the durable `ExecutionHandoffStore` journal, `ARTIFACT_RECORDED`, a non-null artifact ID, and exactly one model HTTP call. Post-revocation replay must fail closed.
- **Revoke-first:** a durable operator revocation precedes launching a separate governed publisher JVM. It must fail without HTTP invocation, final file, or effect journal.
- Existing `.18` negative staging recovery tests and prior positive cross-JVM crash/replay tests remain part of the regression suite.

## Limits and release verdict

This is an acceptance **candidate**, not an observed test result. `300ms` waiting check is a bounded contention observation, not a proof that the revoker reached the OS-lock boundary. No guarantee of SQLite/filesystem atomicity, fsync/power-loss durability, distributed fencing, hostile filesystem mutation or globally exactly-once external HTTP. Operator approval, Fedora XML reports, CI link, and documented deployment assumptions are still required for a formal GO decision. **NO-GO pending verification and assurance review.**

Run `./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks`, then `./gradlew clean test --no-daemon --console=plain` and `git diff --check`.
