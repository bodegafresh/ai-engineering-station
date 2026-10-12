# v0.15 — Event Bus coordination (incremental foundation)

## Purpose

Use the **local Vert.x Event Bus** for independent specialist steps and a deterministic DAG barrier for dependent steps. The worker executes through `TeamCoordinator.AgentExecutor`; inject `ModelAwareTeamExecutor` from v0.14 to select each specialist's configured model.

## Guarantees and limitations

- The coordinator is the **only** component that approves step results and unlocks dependent steps.
- All independent steps in a DAG layer are submitted before awaiting responses. Review happens before the next layer starts.
- Failed/rejected predecessors block their dependents. Cycles are detected as blocked, not executed.
- The local Event Bus is **not durable** and **not distributed**. Restart recovery, idempotency, retries, leases and durable outbox are deferred.
- Delivery timeout is not cancellation of underlying inference. Ensure model adapters have their own deadlines and cancellation mechanisms.
- No network or cloud calls are introduced by this increment. The agent executor decides whether to call a local model.
- `run()` is blocking and MUST be called from a worker thread, never from the Vert.x event loop.
- One coordinator instance supports one active run; use unique instance IDs for multiple concurrent teams.
- This first increment provides a **single local consumer**. For multiple worker instances, define worker addresses/registration and resource admission control before enabling clustering.
- Review is sequential on the calling thread. Parallel review is a later enhancement.
- `maxParallel` bounds concurrent worker invocations, but submissions above this count may queue in `executeBlocking`.

## Integration sketch

```java
// Obtain the injected Vertx instance and your existing ModelAwareTeamExecutor.
// The executor must already be configured with a ModelRouter and a real Invocation.
try (var coordinator = new EventBusTeamCoordinator(
        vertx, "team-001", 2, Duration.ofSeconds(120), modelAwareTeamExecutor)) {
    TeamCoordinator.Report report = coordinator.run(plan, reviewer);
}
```

The `TeamPlan` and reviewer are the existing v0.13 types. This integration does not replace existing REST endpoints and does not automatically wire the application runtime.

## Verification

```bash
./gradlew :apps:control-plane:compileJava --no-daemon --console=plain
./gradlew clean test --no-daemon --console=plain
bash scripts/v07-smoke.sh
git diff --check
git status --short
```

## Acceptance tests to add before production

1. Two independent steps run concurrently (latches, not sleep-based timing).
2. A dependent step starts only after its parent has been approved.
3. Rejected/failed parent blocks descendants.
4. Cyclic graph does not dispatch.
5. Worker exception, missing evidence and reviewer exception yield explicit failures.
6. Parallelism limit and multiple teams with unique addresses.
7. Model selection records specialist/model in an auditable execution trace.
8. Timeout, restart, cancellation and duplicate-delivery scenarios.

## Roadmap

v0.16: Ollama discovery and inference adapter, real per-specialist execution and telemetry. v0.17: durable task state, retries with idempotency, recovery and distributed worker topology.
