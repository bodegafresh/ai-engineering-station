# H02-D2C2f.17 — Publication lock-path hardening

Baseline: `2a938512de4306a338a7997478ea8312addca04f`.

## Threat and change

The existing OS advisory gate followed symbolic links at the lock-file path. An attacker or misconfigured deployment able to replace the lock path could redirect the lock to a different inode, break coordination or cause unintended writes. The gate now fails closed on symlink/non-regular lock paths, uses `NOFOLLOW_LINKS` at open, and checks the immediate lock directory. Negative tests cover symlink target integrity, directory substitution and recovery after removal of an invalid path.

## Contract and boundaries

- Cooperative single-host local filesystem only; all writers and revokers must use the same policy DB and stable lock file.
- The filesystem parent directories must be trusted, non-hostile and not concurrently replaced. This change does **not** provide atomic protection against directory renames, hardlink replacement, malicious privileged actors, or a distributed fencing token.
- Never delete/recreate the lock file while participating processes are active.
- `FileChannel.lock()` still supplies ordering for the critical section, not atomicity across SQLite, filesystem and communication journal.

## Required Fedora acceptance

```
./gradlew :apps:control-plane:test --tests '*GovernedPublicationGateSafetyTest' --tests '*GovernedPublicationGateProcessTest' --tests '*GovernedRevocationCrossJvmTest' --no-daemon --console=plain --rerun-tasks
./gradlew clean test --no-daemon --console=plain
git diff --check
```

## Decision

**NO-GO global.** This is a security hardening sub-slice, not the full end-to-end publication certification originally targeted for .17. The next acceptance must exercise actual governed `WorkspaceDocumentationService` publication/recovery with independent revocation, staged-file corruption, and crash points; it must verify journal and communication effects and absence of repeated model HTTP calls. If all required evidence is not available, keep the global closure NO-GO rather than claiming it.
