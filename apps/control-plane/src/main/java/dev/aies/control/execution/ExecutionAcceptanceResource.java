package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator-only read boundary. SQLite access must never run on the event loop. */
@Path("/api/v1/execution/workflows/{id}/acceptance")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ExecutionAcceptanceResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name = "aies.execution.acceptance.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "aies.execution.handoff.enabled", defaultValue = "false")
    boolean handoffEnabled;
    @ConfigProperty(name = "aies.agent-communications.data-root", defaultValue = "./data/agent-communications")
    String dataRoot;

    @GET
    public ExecutionAcceptanceManifest.Manifest inspect(
            @HeaderParam("X-AIES-Workspace-Token") String token,
            @PathParam("id") String workflowId) throws Exception {
        authorizer.authorize(enabled && handoffEnabled, token);
        try {
            return new ExecutionAcceptanceManifest(java.nio.file.Path.of(dataRoot), mapper).inspect(workflowId);
        } catch (IllegalArgumentException notFound) {
            throw new NotFoundException("Unknown workflow");
        }
    }
}
