# H02-D2C2f.13d — bounded publication rechecks

## Threat and decision

A process can receive durable SQLite publication admission, then pause while an operator revokes the policy in another JVM. Admission is historical evidence, not a filesystem fencing token. This change adds fresh operator-policy authorization after admission and immediately before moving a staged document into its final path. Recovery of a journaled staging file also requires current authorization before the move. No policy revision is taken from an untrusted request.

## Scope and non-goals

This is a production defense-in-depth improvement, not a complete solution to the stale-writer race. The last authorization read and `Files.move(..., ATOMIC_MOVE)` are not one atomic transaction; revocation can still commit in between. No claim of power-loss durability, cross-store atomicity, coordinated DB/file tamper resistance, or automated ambiguous model retry. Existing no-lease invocation reservation stays intact. Legacy ungoverned execution is unchanged.

## Operator response

On revocation after admission, do not delete or reset invocation reservations or attempt to replay a potentially ambiguous model call. Quiesce all publishers and preserve SQLite databases, WAL files, staging and final document files. Verify journal phase, material SHA, admission audit, and communication artifact before manually deciding whether to resume. Treat mismatches as security incidents, not retryable errors.

## Fedora evidence to collect

1. `./gradlew :apps:control-plane:test --tests '*GovernedDocumentation*' --no-daemon --console=plain --rerun-tasks`
2. `./gradlew clean test --no-daemon --console=plain`
3. `git diff --check`

These commands have not been run by the patch author against the complete project checkout. Do not certify 13d without successful Fedora execution. **Overall H02-D2C2f: NO-GO.**

## Next

13e: test revocation at `AFTER_PUBLICATION_ADMISSION` and `AFTER_FILE_JOURNAL` through the real governed service; test staging recovery under revocation; decide and enforce the final writer-exclusion contract.
