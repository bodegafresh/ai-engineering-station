# AIES v0.22 — Agent proposal import (P0 increment)

**Scope:** Offline strict JSON import into the existing v0.21 CLI `prepare` stage. It is intentionally not connected to a model-facing REST endpoint. No agent receives `approve` or `apply` access. Agent text is untrusted.

## Workflow

1. `python3 scripts/aies_p0.py --state-root "$STATE" init --repo "$REPO" --run demo1`
2. Obtain model-generated JSON and review it before saving as `agent.json`. Required schema:

```json
{
  "schemaVersion": 1,
  "runId": "demo1",
  "objective": "Add validation to App.java",
  "changes": [
    {
      "path": "App.java",
      "beforeSha256": "<64 lowercase hex chars of original bytes>",
      "replacement": "class App { /* replacement source */ }\n"
    }
  ]
}
```

3. `python3 scripts/aies_agent_bridge.py --state-root "$STATE" --run demo1 --agent-json agent.json`
4. Inspect the proposal using `scripts/aies_p0.py ... diff --id ID`.
5. A human must separately invoke the existing `approve` and `apply` commands. Never pass approval tokens to a model.

Validation rejects extra fields, wrong run, stale SHA-256, symlinks, traversal, oversized replacements, duplicate files, no-op changes and invalid UTF-8. Proposal is initially `PREPARED` with no approval. SHA-256 of source document and objective are recorded in audit metadata (not proof of identity).

## Tests

```bash
python3 -m unittest discover -s tests -v
./gradlew clean test --no-daemon --console=plain
bash scripts/v07-smoke.sh
```

## Remaining P0 (NOT closed)

- **Critical:** This is a CLI import, not runtime agent-to-proposal wiring. TeamExecutionResource still emits free-form text, not this schema.
- **Critical:** Filesystem TOCTOU/symlink races remain in v0.21 path handling. Restrict to trusted local repos and nonprivileged user.
- **Critical:** Multi-file crash recovery is manual; no transactional commit/revert guarantee.
- **Critical:** `approve --actor` accepts unverified free text. Require independent human identity and role separation before network exposure.
- **Critical:** Sandbox test jobs are Docker-based but not a verified isolation boundary; allowlisted commands, immutable image digests, exit codes and artifact evidence need integration.
- **Critical:** Security E2E for concurrency, replay, prompt injection, crash/restart and secret exposure, plus observability and quality gates.

Do not merge based only on isolated tests. Run complete suite in target repo. No autonomous editing of production projects.
