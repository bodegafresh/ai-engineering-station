package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Server-side authorization boundary for governed generic Ollama inference.
 * No Event Bus field may grant permissions or choose the tool/action/resource.
 * Operator provisioning is deliberately out of scope.
 */
final class GovernedInferenceBoundary {
    private GovernedInferenceBoundary() {}

    static GovernedToolResourcePolicy.Request request(JsonObject body) {
        Objects.requireNonNull(body, "body");
        String id = body.getString("specialistId");
        return new GovernedToolResourcePolicy.Request(
            body.getString("workflowId"), body.getString("taskId"),
            body.getString("attemptId"), id,
            body.getString("governedSpecialistVersion"),
            "ollama", "invoke-model", "ollama:" + id);
    }

    static void require(JsonObject body, Path dataRoot, ObjectMapper mapper) throws Exception {
        var grant = request(body);
        var attempt = new ExecutionHandoffStore.GovernedAttempt(
            grant.workflowId(), grant.taskId(), grant.attemptId(),
            grant.specialistId(), grant.specialistVersion(),
            body.getString("capability"),
            body.getString("governedManifestSha256"),
            body.getLong("governedRegistryRevision", -1L));
        try (var policies = new GovernedToolResourcePolicyStore(
                 dataRoot.resolve("governed-tool-policies.sqlite"));
             var handoff = new ExecutionHandoffStore(
                 dataRoot.resolve("execution-handoff.sqlite"), mapper)) {
            policies.requireAuthorizedCurrent(handoff, attempt, grant);
        }
    }
}
