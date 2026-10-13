package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Authenticated, opt-in read-only recovery endpoint. No operator command is executed. */
@Path("/api/v1/execution/workflows/{id}/recovery")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ExecutionRecoveryResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.recovery.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.execution.handoff.enabled", defaultValue="false") boolean handoffEnabled;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String root;

    @GET
    public ExecutionRecoveryReport.Report inspect(
            @HeaderParam("X-AIES-Workspace-Token") String token,
            @PathParam("id") String workflowId) throws Exception {
        authorizer.authorize(enabled && handoffEnabled, token);
        try {
            return new ExecutionRecoveryReport(java.nio.file.Path.of(root), mapper).inspect(workflowId);
        } catch (IllegalArgumentException invalid) {
            throw new NotFoundException();
        } catch (SecurityException corrupt) {
            throw new WebApplicationException("Recovery state integrity failure", 409);
        }
    }
}
