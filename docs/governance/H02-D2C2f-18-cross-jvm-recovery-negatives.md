# H02-D2C2f.18 — cross-JVM governed staging recovery negatives

Baseline: `60e1494a695a7bc6376f494d066a9d137019a9ff` (.17 merged).

## Scope

Two real independent JVMs using production `WorkspaceDocumentationService`, real activated specialist registry, policy/handoff SQLite databases, local filesystem and local HTTP model fixture. A publisher JVM is terminated abruptly at `AFTER_FILE_JOURNAL` (exit 73); a distinct recovery JVM attempts to materialize the pending staging file.

1. **Revoked staging:** revoke policy after the crash, before recovery. Recovery must exit nonzero, final output must not exist, and HTTP call count remains exactly one.
2. **Tampered staging:** alter staged bytes without updating durable effect hash. Recovery must exit nonzero, final output must not exist, and HTTP call count remains exactly one.

Tests extend `GovernedDocumentationConsumerE2ETest` and reuse established subprocess fixtures. No production behavior or storage schema is changed.

## What this does not certify

These are negative recovery cases, not a complete publish/revoke race proof. No power-loss/fsync durability, distributed fencing, noncooperating filesystem writer, SQLite/filesystem atomicity, or globally exactly-once HTTP semantics. Existing positive restart and two-JVM contender tests remain mandatory. Overall H02-D2C2f stays **NO-GO** until a reviewed acceptance matrix and reproducible end-to-end race evidence support a bounded verdict.

## Validation (Fedora 44)

```bash
./gradlew :apps:control-plane:test --tests '*GovernedDocumentationConsumerE2ETest' --no-daemon --console=plain --rerun-tasks
./gradlew clean test --no-daemon --console=plain
git diff --check
```

Do not mark these tests passed until the commands actually succeed on Fedora. Record JDK, Gradle, SHA, XML results, CI URL, reviewer and accepted exceptions.
