# H02-D2C2f governed recovery — operator runbook (draft, non-automated)

## Scope
For an interrupted governed documentation attempt, retain evidence and prevent duplicate external invocations. This runbook does **not** authorize manual edits to SQLite or retries.

## Triage
1. Stop new dispatches for the affected workflow/attempt through the supported operator controls. Preserve the original `workflowId`, `taskId`, `attemptId`.
2. Collect timestamped logs, service version, policy DB, execution handoff DB, communication DB and workspace files, including SQLite `-wal` and `-shm` companions where present. Make a consistent offline backup; do not copy a live SQLite file alone.
3. Confirm operator identity and approval provenance, current specialist assignment, immutable attempt and policy revision.
4. Determine the latest persisted execution effect: reservation, `FILE_WRITTEN`, moved final file, communication append, or `ARTIFACT_RECORDED`.
5. Require a durable publication admission for any reconciliation. Missing admission or mismatched identity => quarantine; **no replay**.
6. Compare expected source/document SHA-256 with actual bytes. Symlinks, unexpected paths, mismatches or missing journal => quarantine; do not regenerate.
7. If HTTP invocation was reserved but no durable output exists, classify as **ambiguous external outcome**. Never retry automatically; escalate to a human reviewer.
8. For a recoverable post-admission state, use the existing guarded service recovery path in a controlled maintenance environment, with one operator and no competing recovery worker; verify stable artifact ID and no new HTTP request.
9. Retain before/after hashes, audit trail, timestamps and explicit operator sign-off.

## Stop conditions
- Policy absent or revoked before admission.
- Inconsistent identity, journal, source hash or artifact hash.
- Unexplained multiple artifacts, concurrent recovery, SQLite corruption or uncertain backup consistency.
- Any request to manually bypass authorization or reset the invocation reservation.

## Operational gaps (12r candidates)
No automated CLI, dedicated alerting, tested concurrent recovery coordination, power-loss certification or SQLite COMMIT fault injection is supplied by 12q. Do not imply otherwise.
