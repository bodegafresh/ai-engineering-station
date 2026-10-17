package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import io.smallrye.common.annotation.Blocking;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.Path;
import java.time.Instant;
import java.util.List;

/**
 * Opt-in operator-only provisioning. A model or Event Bus cannot approve grants.
 * Requires the same workspace operator credential as existing operator actions.
 */
@Path("/api/v1/execution/governed-policies")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class GovernedPolicyOperatorResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.governed-policy-operator.enabled",defaultValue="false")
    boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications")
    String dataRoot;

    public record Grant(String tool,String action,String resource) {}
    public record Approval(String workflowId,String taskId,String attemptId,
                           String specialistId,String specialistVersion,String capability,
                           String manifestSha256,long registryRevision,long policyRevision,
                           Instant expiresAt,List<Grant> grants) {}

    @POST @Path("/approve")
    public java.util.Map<String,String> approve(
            @HeaderParam("X-AIES-Workspace-Token") String token, Approval input) {
        authorizer.authorize(enabled,token);
        try {
            if(input==null || input.grants()==null || input.grants().isEmpty())
                throw new IllegalArgumentException("Missing grants");
            var attempt = new ExecutionHandoffStore.GovernedAttempt(
                input.workflowId(),input.taskId(),input.attemptId(),
                input.specialistId(),input.specialistVersion(),input.capability(),
                input.manifestSha256(),input.registryRevision());
            var grants = input.grants().stream()
                .map(g -> new GovernedToolResourcePolicy.Grant(g.tool(),g.action(),g.resource()))
                .toList();
            var snapshot = new GovernedToolResourcePolicy.Snapshot(
                input.workflowId(),input.taskId(),input.attemptId(),
                input.specialistId(),input.specialistVersion(),input.policyRevision(),
                input.expiresAt(),false,grants);
            approveVerified(attempt,snapshot);
            return java.util.Map.of("status","APPROVED","attemptId",input.attemptId());
        } catch (Exception e) {
            throw new WebApplicationException("Policy approval rejected",409);
        }
    }

    @POST @Path("/{workflowId}/{taskId}/{attemptId}/revoke")
    public java.util.Map<String,String> revoke(
            @HeaderParam("X-AIES-Workspace-Token") String token,
            @PathParam("workflowId") String wf,
            @PathParam("taskId") String task,
            @PathParam("attemptId") String attempt) {
        authorizer.authorize(enabled,token);
        try (var policies = new GovernedToolResourcePolicyStore(policyPath())) {
            policies.revoke(wf,task,attempt);
            return java.util.Map.of("status","REVOKED","attemptId",attempt);
        } catch(Exception e) {
            throw new WebApplicationException("Policy revocation rejected",409);
        }
    }

    private void approveVerified(ExecutionHandoffStore.GovernedAttempt attempt,
                                 GovernedToolResourcePolicy.Snapshot snapshot) throws Exception {
        try (var handoff = new ExecutionHandoffStore(
                 java.nio.file.Path.of(dataRoot).resolve("execution-handoff.sqlite"),mapper);
             var policies = new GovernedToolResourcePolicyStore(policyPath())) {
            handoff.requireGovernedAttempt(attempt);
            policies.approve(snapshot);
            // Crash between policy approval and binding leaves a non-executable
            // policy; exact replay safely completes the binding.
            handoff.bindGovernedPolicyRevision(attempt,snapshot.policyRevision());
        }
    }

    private java.nio.file.Path policyPath() {
        return java.nio.file.Path.of(dataRoot).resolve("governed-tool-policies.sqlite");
    }
}
