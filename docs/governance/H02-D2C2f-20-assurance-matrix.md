# H02-D2C2f.20 — assurance matrix and evidence protocol

Baseline: `main` `fe215a1f1dc09d4ebddbc3c07cfcdb4f352e8cd9` (.19 merged). This milestone consolidates evidence, **not** a new production security guarantee.

| Invariant | Evidence from real-service tests | Boundary |
|---|---|---|
| Governed capability and grants enforced before side effects | `missingReadGrantCannotInvokeModelOrPublishDocument`, `missingInferenceGrantDeniesBeforeExternalHttp`, `exactGrantsGenerateOnceAndRevocationPreventsReplay` | cooperative trusted registry and local SQLite |
| Revocation/publication ordered across JVMs | `crossJvmPublicationCompletesBeforeOperatorRevocationAcknowledgement`, `crossJvmRevocationFirstDeniesDocumentPublication`, `revocationDuringHttpDeniesPublicationAndLeavesAmbiguousReservation` | local OS locks; bounded race observation, not linearizability proof |
| Crash/recovery fails closed | `separateJvmRecoversAfterAbruptCrashAtEveryPublicationBoundary`, `revokedStagingCannotBeRecoveredByIndependentJvm`, `tamperedStagingCannotBeRecoveredByIndependentJvm`, `twoJvmCompeteToRecoverUnfinishedPublication` | process crash only, not sudden power loss |
| Replay and external calls controlled | `twoJvmConcurrentReplayUsesOneDurableArtifact`, `restartAfterArtifactAppendDoesNotDuplicateEvidence`, `ambiguousReservedInvocationCannotBeAutomaticallyRetried` | not globally exactly-once HTTP |
| Staging/final digest integrity | `alteredStagingIsRejectedWithoutReinvokingModel`, `alteredFinalMaterialIsRejectedWithoutReinvokingModel`, `alteredCompletedArtifactIsRejectedOnReplay` | not protection against hostile filesystem owner |
| Audit failure denies publication | `eventBusAuditWriteFailureDeniesBeforeHttp`, `postHttpAuditFailureDeniesPublicationAndPreservesAmbiguousReservation`, `restartReconcilesEveryPublicationBoundaryWithoutSecondHttp` | no atomic commit across SQLite/filesystem |

## Repeatable local evidence collection

From repository root on Fedora with Java 21:

```bash
./gradlew clean test --no-daemon --console=plain --rerun-tasks
python3 -m unittest discover -s scripts/governance -p 'test_*.py' -v
python3 scripts/governance/assurance_report.py --reports . --output build/assurance/h02-d2c2f20.json
sha256sum build/assurance/h02-d2c2f20.json
```

The report parses `TEST-*.xml` from Gradle, requires exact evidence of named tests, rejects missing/failed/skipped/duplicate/malformed evidence, and requires at least 100 distinct test cases to reject a targeted-only run. It does **not** establish freshness or repository provenance by itself: archive Git SHA, OS/JDK, Gradle output, report digest, CI link, and reviewer identity separately. Do not commit machine-specific generated reports.

## Sign-off checklist

- [ ] Clean `main` or PR SHA recorded; Gradle full rerun successful and XML report archived.
- [ ] All six matrix categories PASS in machine report.
- [ ] Fedora host and Java 21 version recorded; local filesystem and canonical DB path verified.
- [ ] Operator verifies cooperative writer topology, same lock inode, no lock deletion during operation.
- [ ] Known limits acknowledged: no NFS/network FS, hostile mutation, hardlink defense, distributed fencing, cross-store atomicity, power-loss fsync durability, or global exactly-once HTTP.
- [ ] Independent reviewer approves bounded local-use decision and links evidence.

**Current status: NO-GO pending local run and human sign-off.** A green XML report alone never flips the release verdict.
