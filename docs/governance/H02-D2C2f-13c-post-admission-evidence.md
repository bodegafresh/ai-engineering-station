# H02-D2C2f.13c — Post-admission stale JVM evidence

**Base:** `3985ea3e65dc04b86594fb26a7fdf3b7798644cf` (PR #144 merged).

## Experiment

A child JVM commits one publication admission and signals a file barrier. A second JVM revokes the operator policy while the first is suspended. The first JVM then resumes, observes the historical admission, and is denied a second admission. The test asserts exactly one durable `PUBLICATION_ADMISSION` / `ALLOW` audit. It uses independent JVM processes and a deterministic ready/release barrier.

## Decision and boundary

A durable admission is a historical fact, **not** a monotonically fenced filesystem-write lease. Revocation after admission cannot by itself invalidate bytes an already-authorized JVM might write later. This experiment does not execute `WorkspaceDocumentationService`, move a file, or prove prevention of stale filesystem writers. No production security guarantee is added. Therefore global H02-D2C2f certification remains **NO-GO**.

## Operator runbook (interim)

1. Quarantine attempts with ambiguous post-admission publication; do not automatically reassign or reissue the model call.
2. Stop competing writers before filesystem repair. Snapshot policy, handoff and communication SQLite files (including WAL/SHM consistently) and the generated-docs directory.
3. Reconcile policy admission, effect phase, artifact identity and SHA-256 against actual file bytes. Treat mismatches as incidents, not replay candidates.
4. Restore or repair only through an approved operator procedure; retain immutable incident evidence and approval trail.
5. Do not claim power-loss durability or cross-store atomicity from this test.

## Validation required on Fedora

- `./gradlew :apps:control-plane:test --tests '*GovernedPublicationProcessTest' --no-daemon --console=plain --rerun-tasks`
- `./gradlew clean test --no-daemon --console=plain`
- `git diff --check`

## Next

13d: pause a real `WorkspaceDocumentationService` after admission, exercise another JVM against the same workspace, and either implement a verified write fence or formally restrict deployment to one writer with enforced operational controls.
