package dev.aies.control.execution;

import dev.aies.control.planning.PlanningContracts;
import io.vertx.core.json.JsonObject;
import java.util.Objects;

/** Validate executor responses; a successful claim is never proof of execution. */
public final class ExecutionWorkerPolicy {
    private ExecutionWorkerPolicy() {}
    public static String content(JsonObject response) {
        Objects.requireNonNull(response, "Missing specialist response");
        if (!Boolean.TRUE.equals(response.getBoolean("executed")))
            throw new IllegalStateException("Specialist did not execute work");
        String output = response.getString("content");
        if (output == null || output.isBlank() || output.length() > 262144)
            throw new IllegalArgumentException("Invalid specialist output");
        return output;
    }
    public static PlanningContracts.Task task(PlanningContracts.Plan plan, String taskId, String specialistId) {
        if (plan == null || plan.tasks() == null) throw new IllegalArgumentException("Missing plan");
        return plan.tasks().stream().filter(t -> t.id().equals(taskId) && t.specialistId().equals(specialistId))
            .findFirst().orElseThrow(() -> new IllegalArgumentException("Unassigned task"));
    }
}
