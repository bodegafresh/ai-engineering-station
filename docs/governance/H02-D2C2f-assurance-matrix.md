# H02-D2C2f governance assurance matrix

Baseline `6644338821a9571de2b90bfae0b37676cf0b95ce` (PR #131). Status is based on reviewed repository implementation and test design; **not** a fresh Fedora run. `Covered` means bounded scenario coverage, not a universal guarantee.

| Invariant | Evidence in repository | Status | Remaining verification |
|---|---|---|---|
| Governed selection through CDI Event Bus | `GovernedDocumentationQuarkusPositiveTest` | Covered, bounded | Additional negative CDI cases |
| Publication admission serialized with revocation | `GovernedToolResourcePolicyStore`, `GovernedPublicationAdmissionTest` | Covered, bounded | Concurrent independent-process race |
| Recovery requires durable admission | `WorkspaceDocumentationService`, `GovernedToolResourcePolicyStoreTest` | Covered, bounded | Corrupt/missing DB and tampering matrix |
| No second HTTP after post-journal crash | `GovernedDocumentationConsumerE2ETest`, `GovernedDocumentationProcessCrashChild` | Covered, bounded | Pre-journal ambiguity/manual repair |
| Staging/rename/append recovery | Three publication checkpoints and restart tests | Covered, bounded | COMMIT and disk failure |
| File content integrity | SHA-256 comparisons in service and tests | Covered, bounded | Tampered staging/final and path race |
| Communication append idempotence | `CommunicationStore.appendIdempotent` and replay tests | Covered, bounded | Concurrent append under load |
| Legacy compatibility/default-off | Configuration and existing regression suite | Partially covered | Dedicated release gate |
| Power loss, fsync, kernel crash | No evidence | Not certified | Operational durability policy |
| SQLite/filesystem distributed atomicity | Not provided by architecture | Not guaranteed | Reconciliation contract only |
| Globally exactly-once model execution | Not provided by HTTP semantics | Not guaranteed | Never claim |
| Negative CDI authorization matrix | Existing negative and positive CDI tests | Partially covered | Missing revocation/tamper variants |
| Auditability and on-call recovery | Journal and audit tables | Partially covered | Runbook, alerts, owner and retention |

## Closure verdict

**NO-GO** to close H02-D2C2f as production-certified. **GO** to merge this bounded audit after Fedora tests and reviewer approval. Observability design can be prepared but not used to bypass missing security gates.

## Evidence collection checklist

Record main SHA, JDK/Gradle versions, exact test commands, XML reports, GitHub CI run URL, reviewers, exceptions accepted, and approval date. Do not equate a Gradle `BUILD SUCCESSFUL` with proof that each required failure scenario was exercised.

## H02-D2C2f.12w — bounded negative CDI evidence (Fedora validation pending)

- Real Quarkus-managed Event Bus consumer rejects traversal, absolute source path, wrong source digest, forged attempt and missing workflow ID; each denial must leave HTTP invocation count unchanged.
- `GovernedDocumentationQuarkusPositiveTest` still verifies successful governed generation and idempotent replay.
- These tests cover input and authorization boundaries, **not** every revocation/tampering variant. CDI matrix remains partial.
- Actual JDBC `COMMIT` exception injection, filesystem durability and full cross-store atomicity remain unproven. Overall closure remains **NO-GO**.

## H02-D2C2f.12x — COMMIT acknowledgement ambiguity (Fedora validation pending)

- Real COMMIT followed by injected SQLException; durable read confirms one admission and one ALLOW audit, duplicate denied.
- No inside-COMMIT failure, power-loss durability, complete CDI negatives or fencing proof. **NO-GO** overall.

## H02-D2C2f.12y — isolated negative CDI revocation and source tamper (Fedora validation pending)

- `GovernedDocumentationQuarkusRevocationTest`: independent Quarkus profile/SQLite fixture, tampered source then restored original, durable policy revocation, no HTTP calls and no publication admission.
- Covers **source** tampering and pre-invocation revocation only. Does not prove journal/artifact tamper rejection, revocation after publication admission, global fencing, or power-loss durability.
- Overall H02-D2C2f remains **NO-GO**.

## H02-D2C2f.12z — replay file and journal integrity (Fedora validation pending)

- Real CDI replay must reject altered published file and mismatched persisted journal digest without new HTTP calls.
- **Limits:** coordinated file+DB tampering, compromised privileged writer, fencing and power-loss durability unproven. **NO-GO** overall.


## H02-D2C2f.13a — SQLite admission contention and fencing boundary (Fedora validation pending)

- Two independent connections race to admit one attempt; test requires one winner, one denial and one durable ALLOW audit.
- This only exercises transactional SQLite admission, **not** a monotonic filesystem fencing token or a two-JVM stale-owner scenario.
- Operator runbook and threat model: `docs/governance/H02-D2C2f-13a-concurrency-evidence.md`.
- Global certification remains **NO-GO** until missing properties are proven or explicitly excluded by a reviewed operational contract.

## H02-D2C2f.13b — real two-JVM stale admission gate (Fedora validation pending)

- Independent JVM opens SQLite and pauses at an explicit barrier. A second JVM revokes or admits, then the first resumes. A stale admission must fail; persisted ALLOW/admission count must be zero after revoke, one after prior admission.
- This proves current-state checking at the **SQLite admission boundary**, not exclusion of a writer already past admission from filesystem or artifact side effects. No lease-epoch fencing token has been added.
- **NO-GO** for overall production certification. See `docs/governance/H02-D2C2f-13b-stale-jvm-evidence.md`.

## H02-D2C2f.13c — historical admission is not a fencing token (Fedora validation pending)

- Two JVMs exercise a deterministic pause **after** a durable admission and a separate operator revocation. The committed admission remains observable, but a second admission is denied and the ALLOW audit count is one.
- This demonstrates a residual stale-writer window: admission existence alone does **not** prove that an old JVM cannot write a document after revocation. No filesystem write is exercised by this test.
- **NO-GO** for global closure; require real publication-path fencing evidence or an approved single-writer operational restriction.


## H02-D2C2f.13d — publication rechecks (Fedora validation pending)

- Production now revalidates operator-owned governed authorization after admission, immediately before final atomic file move, and before recovery staging move.
- The policy read and filesystem move are not one transaction; revocation between them remains a possible race. No ownership epoch or filesystem fencing is claimed.
- Evidence: `docs/governance/H02-D2C2f-13d-publication-recheck-evidence.md`. Overall **NO-GO**.


### H02-D2C2f.13e — post-admission revocation checkpoint
- Controlled test at `AFTER_PUBLICATION_ADMISSION` with durable policy revoke and assertions for no output/staging and no repeat HTTP call.
- Scope: one JVM, real service and SQLite, local model fixture; not filesystem fencing, post-journal recovery or two-JVM atomicity. Global NO-GO.


### H02-D2C2f.14 — cooperative publication gate (Fedora validation pending)
- OS advisory `FileLock` and same-JVM fair mutex serialize governed filesystem materialization with operator revocation on a shared local policy database. Recheck under lock closes the earlier recheck-to-move race for cooperating processes.
- Recovery staging move uses the same gate. No distributed fencing, cross-store transaction, power-loss durability, or noncooperating writer protection is claimed.
- Pending: Fedora full suite, cross-JVM revoke-first/publish-first and abrupt termination verification. Global NO-GO until evidence is complete.
