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
