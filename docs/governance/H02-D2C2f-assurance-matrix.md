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
