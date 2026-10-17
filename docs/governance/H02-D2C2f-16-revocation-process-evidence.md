# H02-D2C2f.16 — production revocation versus independent JVM gate

Baseline: `main` b75567150278df4b29264187db9b3ee7063be54b.

## Scope

A child Java process acquires the production `GovernedPublicationGate`. In the test JVM, the real `GovernedToolResourcePolicyStore.revoke` is invoked on the same SQLite policy database. A bounded barrier asserts revocation does not complete while the child owns the gate. Policy evaluation remains ALLOW until the child releases it. After release, the revocation must complete and a fresh policy evaluation must return `REVOKED`.

This closes a production integration gap left by the standalone OS-lock tests in .15. It is **not** a full documentation pipeline test, a crash-recovery proof for staged files, a distributed lock, or a SQLite/filesystem atomic transaction. A bounded timeout alone is not a scheduler-independent proof that the revoker attempted lock acquisition; production test evidence must be evaluated accordingly.

## Validation on Fedora

```bash
./gradlew :apps:control-plane:test --tests '*GovernedRevocationCrossJvmTest' --no-daemon --console=plain --rerun-tasks
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## Decision

**NO-GO** for global closure until deterministic full governed publication/revocation ordering, `.staging` crash recovery and model-invocation nonduplication are demonstrated end-to-end. Do not represent this as a certified final acceptance test.
