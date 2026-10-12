package dev.aies.control.models;

import dev.aies.control.team.TeamCoordinator;
import dev.aies.control.team.TeamPlan;
import java.util.Map;
import java.util.Objects;

/** Bridge to v0.13. Agent invocation is delegated; this class makes no network calls. */
public final class ModelAwareTeamExecutor implements TeamCoordinator.AgentExecutor {
    @FunctionalInterface
    public interface Invocation {
        TeamCoordinator.Result execute(TeamPlan.Step step, ModelSelection model,
                Map<String, TeamCoordinator.Result> approvedDependencies) throws Exception;
    }

    private final ModelRouter router;
    private final Invocation invocation;
    private final java.util.function.Function<TeamPlan.Step, String> specialistId;

    public ModelAwareTeamExecutor(ModelRouter router,
            java.util.function.Function<TeamPlan.Step, String> specialistId,
            Invocation invocation) {
        this.router = Objects.requireNonNull(router);
        this.specialistId = Objects.requireNonNull(specialistId);
        this.invocation = Objects.requireNonNull(invocation);
    }

    @Override
    public TeamCoordinator.Result execute(TeamPlan.Step step,
            Map<String, TeamCoordinator.Result> approvedDependencies) throws Exception {
        ModelSelection selection = router.select(specialistId.apply(step));
        return invocation.execute(step, selection, approvedDependencies);
    }
}
