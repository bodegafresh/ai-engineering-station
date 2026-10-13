package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.planning.PlanningStore;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Map;

/** Operator ingress only. No automatic work or direct specialist invocation. */
@Path("/api/v1/execution") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @Blocking
public class ExecutionHandoffResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.handoff.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.planning.enabled", defaultValue="false") boolean planningEnabled;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String root;
    private void auth(String token) {authorizer.authorize(enabled && planningEnabled, token);}
    private ExecutionHandoffStore open() throws Exception {
        return new ExecutionHandoffStore(java.nio.file.Path.of(root).resolve("execution-handoff.sqlite"),mapper);
    }
    @POST @Path("/workflows/{id}/import")
    public Response importAudited(@HeaderParam("X-AIES-Workspace-Token") String token,
                                  @PathParam("id") String id) throws Exception {
        auth(token);
        try (var planning = new PlanningStore(java.nio.file.Path.of(root).resolve("planning.sqlite"),mapper);
             var execution = open()) {
            var snapshot = planning.find(id).orElseThrow(NotFoundException::new);
            if (!"READY_FOR_ASSIGNMENT".equals(snapshot.status()) || snapshot.auditRecordId() == null || snapshot.plan() == null)
                throw new ClientErrorException("Workflow must have an approved audit",409);
            boolean created;
            try {created = execution.importPlan(snapshot.plan());}
            catch (IllegalArgumentException|IllegalStateException e) {throw new ClientErrorException("Invalid execution plan",409);}
            return Response.status(created ? 201 : 200).entity(Map.of("workflowId",id,"imported",created)).build();
        }
    }
    /** Operator-only, immutable authorization; never accepts a boolean from the model. */
    @POST @Path("/workflows/{id}/tasks/{taskId}/authorize-documentation")
    public Response authorizeDocumentation(@HeaderParam("X-AIES-Workspace-Token") String token,
            @PathParam("id") String id, @PathParam("taskId") String taskId,
            DocumentationAuthorizationRequest request) throws Exception {
        auth(token);
        if(request==null) throw new BadRequestException("Missing authorization");
        try(var store=open()) {
            var a=new ExecutionCapabilityAuthorization(id,taskId,"workspace-documentation",request.sourcePath(),request.sourceSha256());
            boolean created=store.authorizeCapability(a);
            return Response.status(created?201:200).entity(Map.of("authorized",true,"created",created)).build();
        }
    }
    public record DocumentationAuthorizationRequest(String sourcePath,String sourceSha256) {}
    @GET @Path("/workflows/{id}/tasks")
    public Object tasks(@HeaderParam("X-AIES-Workspace-Token") String token,
                        @PathParam("id") String id) throws Exception {
        auth(token);
        try (var execution = open()) {return execution.list(id);}
    }
}
