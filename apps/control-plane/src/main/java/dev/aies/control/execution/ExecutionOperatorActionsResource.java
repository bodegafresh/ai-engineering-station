package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Explicit operator commands; no automatic task chaining or fabricated audit approval. */
@Path("/api/v1/execution/workflows/{id}/operator")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ExecutionOperatorActionsResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.execution.operator-actions.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.execution.handoff.enabled", defaultValue="false") boolean handoffEnabled;
    @ConfigProperty(name="aies.execution.worker.enabled", defaultValue="false") boolean workerEnabled;
    @ConfigProperty(name="aies.execution.quality.enabled", defaultValue="false") boolean qualityEnabled;

    @POST @Path("/run-one")
    public JsonObject runOne(@HeaderParam("X-AIES-Workspace-Token") String token,
                             @PathParam("id") String workflowId) {
        authorizer.authorize(enabled && handoffEnabled && workerEnabled, token);
        return request(ExecutionWorkerEventBus.RUN_ONE, workflowId, null, 125);
    }

    @POST @Path("/tasks/{taskId}/review")
    public JsonObject review(@HeaderParam("X-AIES-Workspace-Token") String token,
                             @PathParam("id") String workflowId,
                             @PathParam("taskId") String taskId) {
        authorizer.authorize(enabled && handoffEnabled && qualityEnabled, token);
        return request(ExecutionQualityEventBus.REVIEW, workflowId, taskId, 185);
    }

    private JsonObject request(String address, String workflowId, String taskId, int timeoutSeconds) {
        try {
            CommunicationContracts.id(workflowId);
            if (taskId != null) CommunicationContracts.id(taskId);
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException("Invalid identifier");
        }
        JsonObject command = new JsonObject().put("workflowId", workflowId);
        if (taskId != null) command.put("taskId", taskId);
        try {
            var reply = vertx.eventBus().<JsonObject>request(address, command,
                new DeliveryOptions().setSendTimeout(timeoutSeconds * 1000L))
                .toCompletionStage().toCompletableFuture().get(timeoutSeconds + 2L, TimeUnit.SECONDS).body();
            if (reply == null) throw new WebApplicationException("Empty internal response", 502);
            return reply;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServiceUnavailableException("Operator action interrupted");
        } catch (TimeoutException timedOut) {
            throw new ServiceUnavailableException("Operator action timed out; reconcile durable state before retry");
        } catch (ExecutionException failed) {
            throw new WebApplicationException("Operator action rejected; inspect durable state", 409);
        }
    }
}
