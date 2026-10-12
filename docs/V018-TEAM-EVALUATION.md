# v0.18 — Local opt-in team evaluation (experimental)

This incremental overlay adds `POST /api/v1/teams/evaluate` and does **not** alter the existing durable task scheduler.

## Actual runtime wiring

- REST request -> three-step `TeamPlan` -> `EventBusTeamCoordinator` -> `ModelAwareTeamExecutor` -> `ModelRouter` -> local Ollama `/api/chat`.
- Ollama `/api/tags` checks installed model names. Selection uses operator-owned `models/routing.json`, cloud opt-in is forced **false**.
- Optional project-scoped SQLite memory is retrieved through `ToolGateway` + explicit `CapabilityPolicy` `memory-read` grant. This is **read-only**; never stores task output automatically.
- Reviewer checks structural evidence, not semantic correctness. QA specialist generates a textual review; **not** an independent quality gate.
- No project filesystem access, shell execution, autonomous tools, writes, or cloud calls.

## Requirements

- Java 21, Gradle and working Quarkus control-plane.
- Ollama on `127.0.0.1:11434` and installed models matching `apps/control-plane/src/main/resources/models/routing.json` (e.g. `qwen2.5-coder:7b`, `qwen3:8b`).
- Three inference steps may be slow. The endpoint is synchronous, experimental and **not a durable team job**.
- One team request per evaluation recommended. No quotas or rate limiting yet; do not expose this port publicly.

## Enable

```bash
export AIES_TEAMS_ENABLED=true
export AIES_TEAMS_TOKEN="$(openssl rand -hex 32)"
export AIES_TEAMS_MEMORY_ENABLED=false
./gradlew :apps:control-plane:quarkusDev
```

In a second terminal, export the **same** token and run `bash scripts/smoke-team-v018.sh`. Do not commit `.env` or tokens.

## Manual request

```bash
curl -sS -X POST http://127.0.0.1:8080/api/v1/teams/evaluate \
  -H 'Content-Type: application/json' \
  -H "X-AIES-Team-Token: $AIES_TEAMS_TOKEN" \
  --data '{"projectId":"hello_aies","objective":"Diseña un servicio REST de tareas con pruebas y riesgos.","useMemory":false}' | python3 -m json.tool
```

## Memory

For `useMemory:true`, set `AIES_TEAMS_MEMORY_ENABLED=true` before starting Quarkus. Reads the v0.17 SQLite DB from `AIES_DATA_DIR` (default `./data`), using projectId and objective as FTS query. Requires valid memory input. No automatic memory writes or inference prompt injection protection beyond delimiting data as untrusted.

## Acceptance and gaps

1. `./gradlew clean test` and `bash scripts/v07-smoke.sh` pass.
2. `bash scripts/smoke-team-v018.sh` returns `COMPLETED` with three approved steps and model IDs.
3. Invalid/missing token returns 404; disabled endpoint returns 404.
4. If Ollama is down or required models are absent, the endpoint returns 503 (or a partial report for step-level failures). No silent cloud fallback.
5. Validate the resulting plan manually against a real project. This does not generate code on disk.
6. Future work: durable team executions, semantic review, approval-backed write tools, observability and rate limits.

**Do not claim the original `audit-main-v017.py` gaps are all resolved**: this is a separate, opt-in runtime. Existing `TaskRuntime` remains single-model; existing `/api/v1/tools` is not routed through the capability gateway.
