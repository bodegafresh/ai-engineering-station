# H02-D2C2f.13e — post-admission revocation evidence

Baseline: `e55917e6c070e4c2e2b765e82059ba3cf6742b67`.

## Test contract

An isolated governed CDI fixture exercises the real `WorkspaceDocumentationService` and a local HTTP model response. The instance-scoped `AFTER_PUBLICATION_ADMISSION` checkpoint revokes the operator policy in durable SQLite. The production authorization recheck must throw `SecurityException` before any staging or final document is created. The test also asserts one model HTTP request, no repeat on a denied second attempt, and preservation of the historical admission record.

## Explicit nonclaims

This is a synchronous checkpoint injection within one JVM, **not** an interprocess filesystem fencing proof. Revocation after the final authorization check may still race with `ATOMIC_MOVE`. Post-journal staging recovery, two-JVM ownership, power-loss durability and cross-store atomicity remain open. Do not automatically retry ambiguous model invocations. Global **NO-GO**.

## Fedora verification

```sh
./gradlew :apps:control-plane:test --tests '*GovernedDocumentationPostAdmissionTest' --no-daemon --console=plain --rerun-tasks
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## Next

H02-D2C2f.13f: post-journal negative test, staging recovery, enforceable single-writer contract or fencing with two independent JVMs.
