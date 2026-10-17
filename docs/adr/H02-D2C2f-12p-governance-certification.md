# ADR H02-D2C2f.12p — Governance certification and transition gate

Status: **Accepted for review; closure decision: NO-GO** (pending Fedora validation and risk remediation).
Baseline: `main` `6644338821a9571de2b90bfae0b37676cf0b95ce`, PR #131.

## Decision

Do **not** claim H02-D2C2f complete on the strength of green regression tests alone. The tested contract is narrower than full operational safety. A bounded H02-D2C3 observability design may proceed in parallel, but enabling autonomous governed execution or expanding privileged effects is **NO-GO** until blockers are resolved.

## Confirmed design facts

- Operator policy SQLite is the admission/revocation serialization authority (`BEGIN IMMEDIATE`); admission is per attempt and remains valid following a later revocation. Revocation before admission denies.
- Execution journal, operator policy database, communication database and workspace filesystem are separate persistence domains. `ATOMIC_MOVE` is not a transaction across those domains.
- A reservation before HTTP intentionally prevents automatic second model calls after ambiguous outcomes.
- Publication recovery checks admission and material hashes. Existing tests exercise controlled exceptions and an abrupt child JVM halt at three post-response boundaries.
- Governed mode remains opt-in. Legacy behavior must not silently inherit new guarantees.

## Threat model and residual risks

1. **Power failure/fsync**: `Runtime.halt` does not model kernel crash, device cache loss or power failure. SQLite journal/WAL durability and directory fsync are not certified.
2. **Pre-journal ambiguous HTTP**: a successful external model response may be lost before durable `FILE_WRITTEN`; do not retry automatically. Manual resolution is required.
3. **Cross-store partial commits**: a file, policy admission, communication artifact and execution effect cannot commit atomically together. Reconciliation is best-effort and journal driven.
4. **Cross-process races**: coverage is not proof of safe behavior for concurrent recovery workers, policy changes, or concurrent attempts.
5. **Integrity**: SHA-256 verifies bytes against recorded hashes; it does not establish trusted authorship, prevent all local tampering or provide a signature.
6. **External HTTP**: one observed request in the fixture is not global exactly-once execution.

## Linearization and revocation contract

The durable admission transaction is the publication authorization decision. A revocation committed before admission denies the attempt. A revocation committed after admission does not retroactively revoke the admitted publication; recovery must still require the durable admission and must not reinterpret an absent admission as a legacy exemption.

## Operational contract

- On ambiguous external invocation: quarantine/hold; no automatic retry.
- On missing admission, invalid journal, altered material, or mismatched identity: fail closed; do not publish or replay.
- On recoverable post-admission partial state: reconcile idempotently; verify bytes and references; retain evidence for operator review.
- Preserve immutable identifiers (`workflowId`, `taskId`, `attemptId`) and correlate journal, policy and artifact records.

## Release gates

**GO** for merging this documentation-only audit after static checker and full Gradle suite pass on Fedora.
**NO-GO** for declaring H02-D2C2f closed or enabling new privileged execution until:
- concurrent-process admission/recovery and revocation races are tested;
- missing/corrupt admission, tampered staging/final files, malformed journal and duplicate append are covered by explicit negative tests;
- SQLite transaction-failure and COMMIT-boundary injection are covered;
- recovery/repair operator procedure, retention, telemetry and alert ownership are defined;
- default-off behavior and legacy compatibility are regression tested;
- evidence links to test reports and reviewed residual-risk acceptance are recorded.

## Next decision

H02-D2C2f.12q: implement missing negative/concurrency/transaction-boundary tests and a safe operator recovery protocol; re-evaluate this ADR and closure matrix. Do not silently mark this ADR's NO-GO as GO.
