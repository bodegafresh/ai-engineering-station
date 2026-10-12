# AIES v0.19 — Read-only workspace evaluation

This opt-in increment extends `POST /api/v1/teams/evaluate` with `useWorkspace: true`.

## Scope
- Each project maps to `${AIES_TEAMS_PROJECTS_ROOT}/${projectId}`. No arbitrary paths accepted from HTTP requests.
- Read-only bounded snapshot, no symlink following, denylisted secrets and build folders, allowlisted text extensions.
- Up to 24 files, 8 KiB per file, 24,000 characters total, depth 7. Reports `workspaceFiles` and `workspaceTruncated`.
- Snapshot is authorized through the existing `CapabilityPolicy` and `ToolGateway` (`workspace-read`).
- Repository contents are untrusted prompt data, not instructions. This is prompt-injection mitigation, **not** a formal guarantee.
- No shell execution, patch application, or project modification.

## Setup

```bash
mkdir -p projects/hello_aies
printf 'public class App { public String hello() { return "hello"; } }\n' > projects/hello_aies/App.java
export AIES_TEAMS_ENABLED=true
export AIES_TEAMS_WORKSPACE_ENABLED=true
export AIES_TEAMS_PROJECTS_ROOT="$PWD/projects"
export AIES_TEAMS_TOKEN="$(openssl rand -hex 32)"
./gradlew :apps:control-plane:quarkusDev
```

In another terminal, export the same token and `bash scripts/smoke-workspace-v019.sh`.

## Known limits
- This is a synchronous three-agent review, not an autonomous code-writing agent.
- Does not execute project tests or verify model assertions.
- Denylist is heuristic and cannot identify every sensitive value in otherwise permitted source files. Only point it at sanitized, trusted sample repositories.
- The project snapshot is not durable; only the existing memory API persists information.
- Local Ollama must be running with configured models.
