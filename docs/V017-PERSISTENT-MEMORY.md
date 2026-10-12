# AI Engineering Station v0.17 — SQLite FTS5 project memory

## Scope
- Durable SQLite-backed project memory, FTS5 lexical ranking, project isolation, retention cap and project deletion.
- Explicitly opt-in, token-protected loopback memory API; default DISABLED.
- Conservative context budget (estimated tokens, **not** an actual tokenizer).
- Existing v0.16 in-memory API preserved; no breaking changes.
- Static runtime-wiring audit with explicit GAP statuses.

## Critical integration status
- Memory API is actually wired through a JAX-RS resource and invokes the SQLite store.
- Memory is **not yet automatically injected into specialist inference**. This requires a carefully controlled integration with the active prompt/executor flow, and approved memory writes after review. Do not mistake the REST endpoint for automatic agent memory.
- EventBusTeamCoordinator v0.15 is still an explicit class; its presence does not establish production runtime use. See `scripts/audit-main-v017.py`.
- Capability ToolGateway remains opt-in; no Google, Grafana, Telegram credentials/connectors were introduced.
- No encryption at rest or per-user ACL. Do not store secrets, personal data, or confidential content without additional safeguards.

## Enable locally
```bash
export AIES_MEMORY_ENABLED=true
export AIES_MEMORY_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(36))')"
./gradlew :apps:control-plane:quarkusDev
```
In another terminal, with the same token:
```bash
curl -fsS -X POST http://127.0.0.1:8080/api/v1/memory/remember \
 -H "X-AIES-Memory-Token: $AIES_MEMORY_TOKEN" -H 'Content-Type: application/json' \
 -d '{"projectId":"demo","kind":"decision","content":"Quarkus and Vertx memory persisted"}'
curl -fsS -X POST http://127.0.0.1:8080/api/v1/memory/retrieve \
 -H "X-AIES-Memory-Token: $AIES_MEMORY_TOKEN" -H 'Content-Type: application/json' \
 -d '{"projectId":"demo","query":"Quarkus","limit":5,"maxEstimatedTokens":256}'
```
Restart and repeat retrieve to verify persistence. Keep token out of logs and shell history. `aies.data.dir` controls the data directory.

## Verification
```bash
./gradlew clean test --no-daemon --console=plain
bash scripts/v07-smoke.sh
python3 scripts/audit-main-v017.py .
git diff --check
```

## Future work
1. Inject retrieved memory into real agent invocation with per-project authenticated context and token budget.
2. Persist only reviewer-approved outcomes with provenance and untrusted-source marking.
3. Add encryption, user/project ACL, retention policy, redaction and audit trails.
4. Wire Event Bus coordinator, model router and ToolGateway into actual runtime with contract tests.
5. Add concurrency and migration tests for multiple processes, backup and recovery.
