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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Read-only operator boundary. JDBC and material file hashing execute on a blocking worker. */
@Path("/api/v1/execution/workflows/{id}/material-acceptance")
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ExecutionMaterialAcceptanceResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;

    @ConfigProperty(name="aies.execution.material-acceptance.enabled", defaultValue="false")
    boolean enabled;
    @ConfigProperty(name="aies.execution.handoff.enabled", defaultValue="false")
    boolean handoffEnabled;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications")
    String dataRoot;
    @ConfigProperty(name="aies.execution.documentation.workspace-root", defaultValue="./projects")
    String workspaceRoot;

    @GET
    public ExecutionMaterialAcceptance.Result inspect(
            @HeaderParam("X-AIES-Workspace-Token") String token,
            @PathParam("id") String workflowId) throws Exception {
        authorizer.authorize(enabled && handoffEnabled, token);
        // No user-supplied paths: both roots are operator-owned configuration.
        try {
            return new ExecutionMaterialAcceptance(
                java.nio.file.Path.of(dataRoot), java.nio.file.Path.of(workspaceRoot), mapper)
                .inspect(workflowId);
        } catch (IllegalArgumentException missing) {
            throw new NotFoundException("Unknown workflow");
        } catch (SecurityException invalidMaterial) {
            // Do not return filesystem paths, hashes or internal verifier exceptions.
            throw new WebApplicationException("Material verification failed", 409);
        }
    }
}
