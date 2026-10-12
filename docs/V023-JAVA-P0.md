# AIES v0.23 — Java P0 integration foundation

All new production P0 endpoints and state transitions live in Java/Quarkus. Python remains tooling only.

## Scope

- Java endpoint `POST /api/v1/p0/prepare`, `GET /api/v1/p0/{id}`, `POST /api/v1/p0/{id}/approve`, `POST /api/v1/p0/{id}/apply`.
- Multi-file replacement proposals (up to 12 existing files, 32 KiB each), expected SHA-256 hashes, fingerprint confirmation.
- No model-issued approval. Applies exclusively to detached Git worktree under sessions root; never directly to source checkout.
- Rejects dirty source checkouts and symbolic links along changed paths.
- Disabled by default; independent 32+ character token.

## Configuration

```
AIES_P0_ENABLED=true
AIES_P0_TOKEN=<random 32+ character secret>
AIES_P0_SESSIONS_ROOT=/absolute/path/outside/repositories
AIES_TEAMS_PROJECTS_ROOT=/absolute/path/to/projects
```

Keep the sessions root outside projects root. Each project must be a Git repository with a clean working tree.

## Safety limitations — DO NOT LABEL P0 CLOSED

- Proposals and approvals are in memory, not durable. Restart loses pending state.
- Approval token authenticates an operator, not independently verified human identity.
- No sandboxed tests or verified QA evidence; no agent-to-proposal schema conversion.
- Git process executes a fixed allowlisted command but is not a full OS sandbox.
- Git worktree creation and multi-file replacement are not crash-atomic; failed runs require manual cleanup.
- No inter-process locking; no protection against malicious concurrent filesystem modifications.
- No permission or ACL preservation guarantees.

These are release-blocking for unattended use and P0 completion. Do not enable on untrusted repositories.
