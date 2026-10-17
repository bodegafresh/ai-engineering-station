# H02-D2C2f.15 — Cross-JVM lock acceptance

Baseline: `main` at `8aa3c2d118618b545643e6cea83eba4c9aaab10e`.

## Evidence added

- `GovernedPublicationGateProcessChild`: isolated Java process using only the actual production gate, stdout READY barrier and stdin RELEASE barrier.
- `GovernedPublicationGateProcessTest.independentJvmWaitsUntilOwnerReleases`: second JVM cannot enter before first releases; both exit successfully.
- `GovernedPublicationGateProcessTest.abruptJvmDeathReleasesOsLock`: forcibly kill holder; fresh JVM can acquire the same OS lock.
- Existing tests continue covering operator policy decisions, in-process exclusion and post-admission revocation.

## Limitations and honest certification boundary

These process tests verify the gate itself, not an end-to-end concurrent operator revocation against an actual document publication, nor staging journal/hash recovery after a kill. No automatic model retry is introduced. The project must not claim full cross-store atomicity, distributed fencing, protection against non-cooperating writers or power-loss durability. The global H02-D2C2f decision remains **NO-GO** pending end-to-end two-JVM revocation/publication and crash recovery tests with persisted-effect assertions.

## Fedora validation

```bash
./gradlew :apps:control-plane:test --tests '*GovernedPublicationGateProcessTest' --no-daemon --console=plain --rerun-tasks
./gradlew clean test --no-daemon --console=plain
git diff --check
```
