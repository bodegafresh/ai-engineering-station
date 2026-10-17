# H02-D2C2f.12s — Cross-process policy ordering evidence

Baseline `main` `409cfdc5c6216af43f1f28c9ecb0082c2633c15c` (PR #134).

## Test scope
Three real child-JVM scenarios share a policy SQLite database: revocation committed before admission; admission committed before revocation; and two simultaneously launched independent processes. The parent verifies persisted admission state and requires the admission decision to agree with that state. The subprocess harness enforces a 30-second timeout and rejects unexpected exit codes.

## Boundaries
This proves neither concurrent document recovery nor cross-store atomicity. No deterministic SQLite `COMMIT` fault injection, negative CDI matrix, fsync/power-loss guarantee or external HTTP exactly-once claim is delivered. H02-D2C2f remains NO-GO. Next 12t should address concurrent document recovery and COMMIT fault injection, with production fixes if unsafe behavior is found.
