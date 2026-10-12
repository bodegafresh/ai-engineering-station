# AIES v0.21 preview — P0 workflow (CLI, opt-in)

## Scope and safety
- Uses `git worktree add --detach` on a clean repository, preserving the main working tree.
- Explicit JSON replacements for existing files only (max 20 files, 32 KiB each).
- Diff before human approval; digest confirmation and a one-time token, TTL 15 min.
- SQLite audit of prepare/approve/apply/test events. Does not store identity proof: `--actor` is self-asserted.
- Applies replacements only to detached worktree, not original. Preflight hash checks and best-effort rollback on exceptions.
- Optional Docker test runner: no network, read-only bind mount, no privileges, CPU/RAM/PID/time limits; image must be pinned by SHA256 ID.
- No automatic agent write permissions or code execution by model output.

## Quick start
From AIES root, with a **clean** sample Git repository at `projects/sample`:

```bash
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" init --repo "$PWD/projects/sample" --run demo01
cat >/tmp/aies-changes.json <<'JSON'
{"App.java":"class App { int value; }\n"}
JSON
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" prepare --run demo01 --changes /tmp/aies-changes.json
# Copy the ID and digest from the JSON output.
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" diff --id PROPOSAL_ID
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" approve --id PROPOSAL_ID --digest DIGEST --actor local-human
# Copy oneTimeApproval. This is sensitive and should not be logged or shared.
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" apply --id PROPOSAL_ID --approval ONE_TIME_APPROVAL
```

The repository `projects/sample` must itself be a Git repository. For testing, create one separately. Never run against a valuable repository until security review.

Docker example (image must be a locally available, pinned `sha256:<64hex>` image):

```bash
python3 scripts/aies_p0.py --state-root "$PWD/data/p0" test --run demo01 --image sha256:YOUR_64_HEX_DIGEST java -version
```

This Docker test mounts source read-only, so builds that need to write outputs into the source tree will fail; a follow-up runner should use a disposable writable copy inside the container. Do not assume Docker alone is a strong isolation boundary against a hostile kernel-level adversary.

## Known P0 gaps, **not completed**
1. Quarkus REST / agent bridge: proposals are human-authored JSON, not parsed from agent output.
2. Human identity separation: actor is not authenticated; local CLI operator controls both proposal and approval.
3. Durable crash-safe multi-file transaction: rollback only on caught failures, not power loss or SIGKILL.
4. Symlink TOCTOU: path checks mitigate but do not eliminate races; use `openat2`/dirfd secure opens or isolated immutable snapshots for hostile workspaces.
5. Sandbox build writes: current read-only bind mount cannot compile projects writing to workspace. Needs a disposable writable overlay or copy, seccomp policy, cgroup and network enforcement verification.
6. Production integration tests and security review, including prompt injection, SSRF, race/replay, disk failure and concurrent process tests.
7. Worktree lifecycle: garbage collection and explicit deletion/retention policies, quotas and locks across processes.
8. End-to-end traceability: link proposals to model versions, prompts, task IDs, base commit, CI evidence and signed human approval.

## Validation
`python3 -m unittest discover -s tests -v` exercises happy path, stale input, tamper, traversal and symlink rejection. Full Gradle and Quarkus integration not run in package authoring environment.
