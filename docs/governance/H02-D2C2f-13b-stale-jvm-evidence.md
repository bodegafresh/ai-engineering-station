# H02-D2C2f.13b — Two-JVM stale admission evidence

## Threat and invariant

A child JVM opens the SQLite policy store and signals `ready`. Another JVM then either durably revokes the attempt or commits a publication admission. Only after the other JVM exits does the parent release the stale child. The stale JVM must consult current persisted state and refuse a new admission (exit 21). After revocation there is no admission or ALLOW audit; after prior admission there is exactly one of each.

## Reproducibility

Run `./gradlew :apps:control-plane:test --tests '*GovernedPublicationProcessTest' --no-daemon --console=plain --rerun-tasks` and `./gradlew clean test --no-daemon --console=plain` on Fedora. Inspect XML reports and CI checks. Tests use explicit filesystem handshakes; bounded polling is only for synchronization, never as a race outcome oracle. Child processes have timeouts and cleanup.

## Nonclaims / residual risks

This is not a stale writer already **past** publication admission and paused before atomic filesystem move. The test does not prove an epoch-based fencing token, full exactly-once model invocation, malicious journal/file writer exclusion, power-loss durability, or atomicity across SQLite and filesystem. Operator should fail closed on ambiguous reserved model invocation and reconcile persistent evidence before any manual restart. A formal GO requires a reviewed post-admission filesystem-writer threat decision and separate recovery evidence.

## Verdict

GO to merge this bounded test increment after successful Fedora and CI validation. NO-GO to close H02-D2C2f overall.
