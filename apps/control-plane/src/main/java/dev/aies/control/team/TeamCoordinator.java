
package dev.aies.control.team;

import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinates a team of agents using a dependency graph (DAG).
 *
 * Independent steps execute concurrently.
 * Dependent steps execute only after their dependencies
 * have completed and passed review.
 *
 * Execution and review are delegated to explicit adapters.
 *
 * The coordinator does not own the ExecutorService lifecycle.
 */
public final class TeamCoordinator {

    private static final long EXECUTION_TIMEOUT_SECONDS = 120;

    public interface AgentExecutor {
        Result execute(
                TeamPlan.Step step,
                Map<String, Result> approvedDependencies
        ) throws Exception;
    }

    public interface Reviewer {
        boolean approve(
                TeamPlan.Step step,
                Result result
        ) throws Exception;
    }

    public record Result(
            String output,
            List<String> evidence
    ) {
        public Result {
            if (output == null || output.isBlank()) {
                throw new IllegalArgumentException("Empty output");
            }

            evidence = List.copyOf(
                    Objects.requireNonNull(evidence, "evidence")
            );
        }
    }

    public record Report(
            String status,
            Map<String, Result> approved,
            Map<String, String> failures
    ) {
        public Report {
            approved = Map.copyOf(approved);
            failures = Map.copyOf(failures);
        }
    }

    private final ExecutorService pool;

    public TeamCoordinator(ExecutorService pool) {
        this.pool = Objects.requireNonNull(
                pool,
                "ExecutorService cannot be null"
        );
    }

    public Report run(
            TeamPlan plan,
            AgentExecutor executor,
            Reviewer reviewer
    ) throws InterruptedException {

        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(reviewer, "reviewer");

        validatePlan(plan);

        Map<String, TeamPlan.Step> nodes =
                new LinkedHashMap<>();

        for (TeamPlan.Step step : plan.steps()) {
            if (nodes.putIfAbsent(step.id(), step) != null) {
                throw new IllegalArgumentException(
                        "Duplicate step: " + step.id()
                );
            }
        }

        for (TeamPlan.Step step : plan.steps()) {
            if (step.dependsOn().contains(step.id())
                    || !nodes.keySet().containsAll(
                            step.dependsOn()
                    )) {

                throw new IllegalArgumentException(
                        "Invalid dependencies: " + step.id()
                );
            }
        }

        Map<String, Result> approved =
                new LinkedHashMap<>();

        Map<String, String> failures =
                new LinkedHashMap<>();

        Set<String> remaining =
                new LinkedHashSet<>(nodes.keySet());

        while (!remaining.isEmpty()) {

            List<TeamPlan.Step> ready = remaining.stream()
                    .map(nodes::get)
                    .filter(step ->
                            approved.keySet().containsAll(
                                    step.dependsOn()
                            )
                    )
                    .toList();

            if (ready.isEmpty()) {
                for (String id : remaining) {
                    failures.put(
                            id,
                            "Blocked: failed dependency or cyclic graph"
                    );
                }
                break;
            }

            Map<String, Future<Result>> futures =
                    new LinkedHashMap<>();

            try {

                // Submit independent steps concurrently.
                for (TeamPlan.Step step : ready) {

                    Map<String, Result> dependencies =
                            new LinkedHashMap<>();

                    for (String dependencyId : step.dependsOn()) {
                        dependencies.put(
                                dependencyId,
                                approved.get(dependencyId)
                        );
                    }

                    Map<String, Result> immutableDependencies =
                            Map.copyOf(dependencies);

                    Future<Result> future = pool.submit(
                            () -> executor.execute(
                                    step,
                                    immutableDependencies
                            )
                    );

                    futures.put(step.id(), future);
                }

                // Collect execution results and review them.
                for (TeamPlan.Step step : ready) {

                    Future<Result> future =
                            futures.get(step.id());

                    try {

                        Result result = future.get(
                                EXECUTION_TIMEOUT_SECONDS,
                                TimeUnit.SECONDS
                        );

                        if (result == null
                                || result.evidence().isEmpty()) {

                            failures.put(
                                    step.id(),
                                    "Missing output or evidence"
                            );

                            continue;
                        }

                        boolean accepted;

                        try {
                            accepted = reviewer.approve(
                                    step,
                                    result
                            );
                        } catch (Exception reviewException) {

                            failures.put(
                                    step.id(),
                                    "Reviewer execution failed: "
                                            + reviewException
                                                    .getClass()
                                                    .getSimpleName()
                            );

                            continue;
                        }

                        if (!accepted) {

                            failures.put(
                                    step.id(),
                                    "Rejected by reviewer"
                            );

                            continue;
                        }

                        approved.put(
                                step.id(),
                                result
                        );

                    } catch (TimeoutException timeoutException) {

                        future.cancel(true);

                        failures.put(
                                step.id(),
                                "Execution timeout"
                        );

                    } catch (ExecutionException executionException) {

                        Throwable cause =
                                executionException.getCause();

                        String errorType = cause == null
                                ? "Unknown"
                                : cause.getClass().getSimpleName();

                        failures.put(
                                step.id(),
                                "Execution failed: " + errorType
                        );

                    } catch (CancellationException cancellationException) {

                        failures.put(
                                step.id(),
                                "Execution cancelled"
                        );

                    }
                }

            } catch (InterruptedException interruptedException) {

                cancelPending(futures);

                Thread.currentThread().interrupt();

                throw interruptedException;

            } catch (RuntimeException runtimeException) {

                cancelPending(futures);

                throw runtimeException;

            } finally {

                // Ensure unfinished tasks are not left running
                // after the current coordination layer ends.
                cancelPending(futures);
            }

            remaining.removeAll(
                    ready.stream()
                            .map(TeamPlan.Step::id)
                            .toList()
            );
        }

        String status = failures.isEmpty()
                ? "COMPLETED"
                : "PARTIAL_OR_BLOCKED";

        return new Report(
                status,
                approved,
                failures
        );
    }

    private static void validatePlan(TeamPlan plan) {

        if (plan.steps() == null
                || plan.steps().isEmpty()
                || plan.steps().size() > 20) {

            throw new IllegalArgumentException(
                    "Plan must have 1..20 steps"
            );
        }
    }

    private static void cancelPending(
            Map<String, Future<Result>> futures
    ) {

        for (Future<Result> future : futures.values()) {

            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
