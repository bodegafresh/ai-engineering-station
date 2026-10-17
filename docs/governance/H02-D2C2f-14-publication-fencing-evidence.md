# H02-D2C2f.14 — cooperative publication/revocation gate

## Threat and mitigation

The 13d revalidation-to-filesystem-move gap permitted revocation to commit after the last authorization check and before `ATOMIC_MOVE`. Publication and operator revocation now acquire the same host-local advisory file lock (and same-JVM mutex) before their critical sections. Publication rechecks policy under the gate, and keeps it until the move completes; revocation takes the gate before its SQLite `BEGIN IMMEDIATE` and holds it until `COMMIT`.

## Critical semantics

- Revoke-first: the publisher acquires the gate later and fails closed on the fresh policy read.
- Publish-first: the operator revoke waits; its successful acknowledgement occurs after the filesystem move.
- Post-admission checkpoint remains outside the gate, preserving deterministic negative tests.
- Staging recovery revalidates under the same gate immediately before materialization.
- A process death releases its OS lock; its reserved model call remains ambiguous and must not be retried automatically.

## Limitations

This is a cooperative **single-host, local-filesystem** contract, not distributed fencing. All operator revocations and governed publishers must participate and use the same database path. File locks are advisory; direct filesystem writers, unsupported network mounts, path aliasing, OS crash/power loss and cross-store atomicity are outside this guarantee. SQLite commit and filesystem move are not one transaction. Existing journal recovery and operator reconciliation remain necessary.

## Fedora acceptance (pending)

1. `./gradlew :apps:control-plane:test --tests '*GovernedPublicationGateTest' --tests '*GovernedDocumentationPostAdmissionTest' --no-daemon --console=plain --rerun-tasks`
2. `./gradlew clean test --no-daemon --console=plain`
3. `git diff --check`
4. Manually/automatically exercise revoke-first and publish-first with separate JVMs and abrupt termination before declaring global GO.

**Decision:** bounded improvement implemented, global **NO-GO** pending cross-JVM evidence and deployment contract approval.
