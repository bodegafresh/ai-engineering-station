# F4.1 — Deterministic execution integration

## Source baseline
GitHub `bodegafresh/ai-engineering-station`, `main`, reviewed 2026-10-13.
Additive test-only change; no production Java or configuration overwritten.

## Scope
- Real in-process Vert.x Event Bus consumer on existing `ExecutionWorkerEngine.EXECUTE`.
- Actual `ExecutionWorkerEngine`, `ExecutionHandoffStore` and `CommunicationStore` using isolated SQLite files.
- Two-task DAG: task b cannot run before verified approval of a.
- Negative audit decision, positive decision, replay idempotency, durable reopen, no extra invocation.
- Deterministic mock specialist; no real Ollama or external calls.

## Acceptance
`./gradlew :apps:control-plane:test --tests '*F41DeterministicWorkflowE2ETest' --no-daemon --console=plain`
`./gradlew clean test --no-daemon --console=plain`
`git diff --check`

## Not covered / explicit gaps
- Full HTTP intake -> requirements -> plan -> operator approval -> execution path.
- Real Ollama, material workspace, automatic audit consumer, final manifest.
- Process-level restart, crash injection, concurrency across processes, resource budgets.
- No end-to-end certification should be claimed from this integration test alone.

## Next milestones
### F4.2 — opt-in local real-model smoke
Run with real Ollama and a disposable workspace. Authorize the exact capability and
input SHA-256; verify material outputs and quality decision. Report duration,
model name, model calls, artifacts and failure reason. No unapproved shell.

### F4.3 — final delivery + reproducible E2E CLI
Add a read-only durable workflow manifest (task, attempt, artifact, SHA-256, audit,
decision, timestamps, model and limitations). Add smoke shell script with preflight
and negative cases: tampering, absent grant, expired lease, replay and restart.

### F5 — controlled engineering tools
Explicit operator grants, path confinement, Docker/tool resource limits,
reproducible verification; no arbitrary shell or live trading.

### F6 — operations and hardening
Metrics/traces, backpressure, concurrency limits, recovery drills, threat review,
load tests and Gradle deprecation cleanup.

## Engineering principles
Keep Java 21, Quarkus, SOLID, isolated adapters, Vert.x Event Bus for internal
messaging, blocking I/O off event-loop, immutable contracts, durable idempotency,
fail-closed authorization and specialist learning only from verified cases.
