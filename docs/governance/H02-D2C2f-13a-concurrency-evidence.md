# H02-D2C2f.13a — bounded contention and fencing threat model

## Scope
`GovernedPublicationConcurrentAdmissionTest` races two independent `GovernedToolResourcePolicyStore` SQLite connections against the same `(workflow, task, attempt)` admission. The durable database must contain one admission and exactly one `PUBLICATION_ADMISSION` ALLOW audit. A second writer must be denied. The test does not launch two JVMs.

## Threat model and decision
- SQLite `BEGIN IMMEDIATE`, unique admission keys and transactional audit establish a **database admission** boundary. They do not constitute a generation-based fencing token for all filesystem writes.
- A stale process with a prior valid approval may resume after a newer owner has taken over. Unless each protected write checks an authoritative monotonic generation, a stale filesystem writer cannot be ruled out by this test.
- Existing invocation reservation is intentionally non-leased to prevent a silent second model HTTP request. Do not introduce timeout-based automatic takeover without an explicit fencing design and an idempotent external-effect contract.
- SQLite, communication journal and workspace filesystem do not share one atomic transaction. No power-loss durability or malicious coordinated database-and-file tamper resistance is claimed.

## Operator recovery runbook (fail closed)
1. Quiesce documentation consumers on **all** hosts sharing the data root and workspace; prevent new Event Bus deliveries.
2. Preserve read-only copies of `execution-handoff.sqlite`, `governed-tool-policies.sqlite`, `communication.sqlite`, associated WAL/SHM files, and `.aies/generated-docs`; record host, UTC time and hashes. Do not copy live WAL databases without a consistent SQLite backup/snapshot.
3. Inspect `(workflowId, taskId, attemptId)` in the execution effect journal, model invocation reservation and publication admission, and verify generated-file SHA-256 and referenced communication artifact.
4. If an external model invocation is reserved but the publication state is ambiguous, **do not automatically re-invoke** the model. Escalate for manual reconciliation.
5. If evidence hashes or journals disagree, quarantine the attempt; never repair by blindly replacing hashes or deleting rows.
6. Restore consumers only after an operator records the decision, evidence references and any accepted residual risk. Confirm the attempt is not concurrently running elsewhere.

## Validation
`./gradlew :apps:control-plane:test --tests '*GovernedPublicationConcurrentAdmissionTest' --no-daemon --console=plain --rerun-tasks`

`./gradlew clean test --no-daemon --console=plain`

## Certification verdict
**GO** for this bounded concurrency test after Fedora and CI validation; **NO-GO** for global H02-D2C2f certification. Next: 13b actual two-JVM stale-owner write scenario, fencing ADR and enforcement or explicit fail-closed operational limitation.
