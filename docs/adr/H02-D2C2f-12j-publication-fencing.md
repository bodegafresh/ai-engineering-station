# ADR — H02-D2C2f.12j: publication fencing and crash recovery

Status: **Proposed, not implemented**. This document records the next safety boundary; it does not claim a distributed transaction.

## Context

The documentation consumer persists an invocation reservation in the execution handoff SQLite database before external HTTP. It then writes a staging file, records `FILE_WRITTEN`, atomically renames the file, appends a communication artifact in a separate SQLite database, and records `ARTIFACT_RECORDED`. Operator-owned grants and their revocation live in yet another SQLite database. The current post-HTTP policy check closes the demonstrated in-flight revocation window, but revocation can still race after that check and before publication. `ATOMIC_MOVE` is atomic only for the filesystem rename, not for policy, execution, or communication databases.

## Decision for future implementation

1. **Define an explicit linearization point.** A durable, per-attempt publication authorization/fence must be acquired under a writer transaction coordinated with the same durable revocation authority. Revocation and publication admission must have a deterministic total order. A mere second read or in-memory lock is insufficient across processes.
2. **Separate admission from materialization.** After a valid fence is acquired, a journaled state machine should own the publish/reconcile workflow. The contract must specify whether a revocation after admission permits completion of the already committed publication; never silently reinterpret a completed fence as a fresh grant.
3. **Preserve single-flight.** Once HTTP is reserved, failures and crashes must never trigger an automatic second model invocation. `AMBIGUOUS_INVOCATION` requires operator reconciliation or evidence-based recovery.
4. **Use recoverable filesystem states.** Staging, digest and final rename must be verified after restart. Artifact append remains idempotent; reconciliation must not invent a successful audit or imply exactly-once across SQLite and filesystem.
5. **Fail closed on durable audit failure.** An authorization decision without its durable audit record cannot authorize publication.

## Open design choices / prerequisites

- Decide whether to move publication admission and revocation into one SQLite transaction/domain or implement a single-writer publication authority with durable epochs. Avoid cross-database `ATTACH` assumptions without explicit crash-safety verification.
- Specify lease expiry, process death, concurrent workers, policy expiry, and a revocation occurring after admission.
- Add fault-injection seams at journal commit, staging write, rename, communication append and final record. Verify restart behavior and idempotency in separate processes.
- Complete a positive governed `@QuarkusTest` with fully isolated filesystem/SQLite configuration; the existing CDI test covers only negative routing.

## Verification scope in 12j

A deterministic SQLite trigger fails the **third** policy ALLOW (the post-HTTP check). It proves that failed audit persistence denies publication even though the HTTP side effect has already occurred, and that clearing the fault does not replay a reserved invocation. This is a bounded regression, **not** a fencing implementation or a COMMIT-crash test.
