package dev.aies.control.eventbus;

import dev.aies.control.team.TeamCoordinator;
import dev.aies.control.team.TeamPlan;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local Event Bus transport for DAG teams. Independent steps within one layer run
 * concurrently; a subsequent layer starts only after all predecessors are reviewed.
 * Results are accepted exclusively by the coordinator, never by worker messages.
 * This is an in-process transport, not durable/distributed messaging.
 */
public final class EventBusTeamCoordinator implements AutoCloseable {
    private final Vertx vertx;
    private final String address;
    private final MessageConsumer<JsonObject> consumer;
    private final Semaphore capacity;
    private final Duration timeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EventBusTeamCoordinator(Vertx vertx, String instanceId, int maxParallel,
                                   Duration timeout, TeamCoordinator.AgentExecutor executor) {
        this.vertx = Objects.requireNonNull(vertx);
        Objects.requireNonNull(executor);
        if (instanceId == null || !instanceId.matches("[a-zA-Z0-9-]{1,64}"))
            throw new IllegalArgumentException("Invalid instance ID");
        if (maxParallel < 1 || maxParallel > 64) throw new IllegalArgumentException("maxParallel 1..64");
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("Positive timeout required");
        this.address = "aies.team.execute." + instanceId;
        this.capacity = new Semaphore(maxParallel);
        this.timeout = timeout;
        this.consumer = vertx.eventBus().localConsumer(address, message -> {
            JsonObject body = message.body();
            // executeBlocking keeps potentially blocking model calls off the event loop.
            vertx.<JsonObject>executeBlocking(() -> {
                capacity.acquire();
                try {
                    TeamPlan.Step step = findStep(body.getString("stepId"));
                    Map<String, TeamCoordinator.Result> dependencies = dependencyResults(body.getJsonObject("dependencies"));
                    TeamCoordinator.Result result = executor.execute(step, dependencies);
                    if (result == null) throw new IllegalStateException("Null agent result");
                    return new JsonObject().put("output", result.output())
                            .put("evidence", new JsonArray(result.evidence()));
                } finally {
                    capacity.release();
                }
            }, false).onSuccess(message::reply)
              .onFailure(error -> message.fail(500, error.getClass().getSimpleName()));
        });
    }

    // Registry is bound for the duration of one run. One coordinator instance accepts one run at a time.
    private volatile Map<String, TeamPlan.Step> activeSteps = Map.of();
    private final AtomicBoolean running = new AtomicBoolean();

    private TeamPlan.Step findStep(String id) {
        TeamPlan.Step step = activeSteps.get(id);
        if (step == null) throw new IllegalArgumentException("Unknown step");
        return step;
    }

    private static Map<String, TeamCoordinator.Result> dependencyResults(JsonObject json) {
        Map<String, TeamCoordinator.Result> results = new LinkedHashMap<>();
        if (json != null) for (String key : json.fieldNames()) {
            JsonObject item = json.getJsonObject(key);
            results.put(key, new TeamCoordinator.Result(item.getString("output"),
                    item.getJsonArray("evidence").getList().stream().map(String::valueOf).toList()));
        }
        return Map.copyOf(results);
    }

    /** Blocking API: call from a worker or test thread, never from a Vert.x event-loop thread. */
    public TeamCoordinator.Report run(TeamPlan plan, TeamCoordinator.Reviewer reviewer)
            throws InterruptedException {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(reviewer);
        if (closed.get()) throw new IllegalStateException("Coordinator closed");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("Concurrent runs not supported");
        try {
            if (plan.steps() == null || plan.steps().isEmpty() || plan.steps().size() > 20)
                throw new IllegalArgumentException("Plan must have 1..20 steps");
            Map<String, TeamPlan.Step> nodes = new LinkedHashMap<>();
            for (TeamPlan.Step step : plan.steps()) {
                if (step == null || step.id() == null || !step.id().matches("[a-zA-Z0-9-]{1,64}"))
                    throw new IllegalArgumentException("Invalid step");
                if (nodes.putIfAbsent(step.id(), step) != null) throw new IllegalArgumentException("Duplicate step");
            }
            for (TeamPlan.Step step : nodes.values()) {
                if (step.dependsOn() == null || step.dependsOn().contains(step.id())
                        || !nodes.keySet().containsAll(step.dependsOn()))
                    throw new IllegalArgumentException("Invalid dependencies: " + step.id());
            }
            activeSteps = Map.copyOf(nodes);
            Map<String, TeamCoordinator.Result> approved = new LinkedHashMap<>();
            Map<String, String> failures = new LinkedHashMap<>();
            Set<String> remaining = new LinkedHashSet<>(nodes.keySet());
            while (!remaining.isEmpty()) {
                List<TeamPlan.Step> ready = remaining.stream().map(nodes::get)
                        .filter(step -> approved.keySet().containsAll(step.dependsOn())).toList();
                if (ready.isEmpty()) {
                    remaining.forEach(id -> failures.put(id, "Blocked: failed dependency or cycle"));
                    break;
                }
                Map<String, CompletableFuture<JsonObject>> futures = new LinkedHashMap<>();
                for (TeamPlan.Step step : ready) {
                    JsonObject deps = new JsonObject();
                    for (String dep : step.dependsOn()) {
                        TeamCoordinator.Result result = approved.get(dep);
                        deps.put(dep, new JsonObject().put("output", result.output())
                                .put("evidence", new JsonArray(result.evidence())));
                    }
                    JsonObject request = new JsonObject().put("stepId", step.id()).put("dependencies", deps);
                    CompletableFuture<JsonObject> future = new CompletableFuture<>();
                    vertx.eventBus().<JsonObject>request(address, request,
                            new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(timeout.toMillis()))
                            .onSuccess(reply -> future.complete(reply.body()))
                            .onFailure(future::completeExceptionally);
                    futures.put(step.id(), future);
                }
                try {
                    // Layer barrier: all results/reviews finish before the next DAG layer.
                    for (TeamPlan.Step step : ready) {
                        try {
                            JsonObject response = futures.get(step.id()).get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
                            TeamCoordinator.Result result = new TeamCoordinator.Result(
                                    response.getString("output"), response.getJsonArray("evidence").getList()
                                            .stream().map(String::valueOf).toList());
                            if (result.evidence().isEmpty()) failures.put(step.id(), "Missing evidence");
                            else if (!reviewer.approve(step, result)) failures.put(step.id(), "Rejected by reviewer");
                            else approved.put(step.id(), result);
                        } catch (InterruptedException e) {
                            throw e;
                        } catch (TimeoutException e) {
                            failures.put(step.id(), "Timed out");
                        } catch (ExecutionException | CompletionException e) {
                            failures.put(
                                step.id(),
                                "Execution failed: " + e.getClass().getSimpleName()
                            );
                        } catch (Exception e) {
                            failures.put(
                                step.id(),
                                "Review/response failed: " + e.getClass().getSimpleName()
                            );
                        }
                    }
                } catch (InterruptedException e) {
                    futures.values().forEach(f -> f.cancel(true));
                    Thread.currentThread().interrupt();
                    throw e;
                }
                remaining.removeAll(ready.stream().map(TeamPlan.Step::id).toList());
            }
            return new TeamCoordinator.Report(failures.isEmpty() ? "COMPLETED" : "PARTIAL_OR_BLOCKED",
                    approved, failures);
        } finally {
            activeSteps = Map.of();
            running.set(false);
        }
    }

    public String address() { return address; }

    @Override public void close() {
        if (running.get()) throw new IllegalStateException("Cannot close while running");
        if (closed.compareAndSet(false, true)) consumer.unregister();
    }
}
