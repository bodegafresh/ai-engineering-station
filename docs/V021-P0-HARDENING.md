# AIES v0.21 P0 — isolated CLI hardening (not production-ready)

This is an incremental, local human-operated CLI overlay against v0.20 main. It is not wired into Quarkus or agent-generated patches. No model can invoke the CLI from the team evaluator.

## Changes
- Correctly inspect each intermediate directory for symbolic links.
- Use O_NOFOLLOW on file reads and check regular-file identity.
- Reject common sensitive filenames.
- Persist APPLYING before the first filesystem modification; consume the approval token before any write.
- Expose `status` to find APPLYING / RECOVERY_REQUIRED states after crashes.
- Keep explicit git worktrees, per-file hash checks, diff and manual approval.

## Test
```bash
python3 -m unittest discover -s tests -v
./gradlew clean test --no-daemon --console=plain
python3 scripts/aies_p0.py --state-root "$HOME/.local/state/aies-p0" status
```

## Important limitations
- Python path checks + O_NOFOLLOW are not sufficient to eliminate directory symlink races; a directory-fd/openat2 based design or OS isolation is required before untrusted workspaces.
- A crash during multi-file replacement can leave a partial patch; `APPLYING` indicates manual recovery is necessary. Never auto-resume.
- CLI `approve` accepts an arbitrary actor string and is **not** authenticated human identity. Do not expose it to an LLM, plugin, network endpoint, or unattended process.
- SQLite audit has no integrity signature or external log sink.
- The Docker test runner depends on local Docker daemon permissions and its configuration; Docker alone is not a complete security boundary.
- The CLI does not integrate with Quarkus, agent-generated diffs, task IDs, structured model output, or independent QA. All are still P0 gaps.
- No full Gradle test was run in the packaging environment. Do not merge without user-side validation.

## Remaining P0 before autonomous editing
1. Strong filesystem isolation, restricted runtime identity, openat2/dirfd race-resistant operations.
2. Crash-consistent multi-file transactions or git-index-based commit/revert recovery.
3. Human authentication, role separation, one-time approval bound to exact diff and project.
4. Durable task/proposal/approval audit with actor, model, prompt and commit provenance.
5. Allowlisted sandbox build/test jobs with resource/network limits and verified evidence.
6. Security E2E tests for symlink races, replay, prompt injection, secrets and concurrent writes.
7. Structured agent-to-proposal integration through a strictly validated gateway.
