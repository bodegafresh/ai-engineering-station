package dev.aies.control.science;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Operator-only intake/read API. No REST calls between agents. */
@Path("/api/v1/scientific-workflows")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class ScientificWorkflowResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private void authorize(String token) {authorizer.authorize(enabled,token);}
    private ScientificWorkflowStore open() throws Exception {
        return new ScientificWorkflowStore(java.nio.file.Path.of(dataRoot).resolve("science.sqlite"),mapper);
    }
    @POST public Response create(@HeaderParam("X-AIES-Workspace-Token") String token, ScientificContracts.Plan plan) throws Exception {
        authorize(token);
        try(var store=open()) {
            return Response.status(201).entity(store.create(plan)).build();
        } catch(java.sql.SQLException e) {
            if(e.getMessage()!=null && e.getMessage().contains("UNIQUE")) throw new ClientErrorException("Workflow already exists",409);
            throw e;
        } catch(IllegalArgumentException|NullPointerException e) {throw new BadRequestException("Invalid scientific plan");}
    }
    @GET @Path("/{workflowId}") public ScientificWorkflowStore.Snapshot get(
        @HeaderParam("X-AIES-Workspace-Token") String token,@PathParam("workflowId") String workflowId) throws Exception {
        authorize(token);
        try(var store=open()) {return store.find(workflowId).orElseThrow(NotFoundException::new);}
        catch(IllegalArgumentException e) {throw new BadRequestException("Invalid workflow ID");}
    }
    public record EvidenceInput(String experimentId,double metricValue,int sampleSize,String notes) {}
    @POST @Path("/{workflowId}/evidence") public Response evidence(
        @HeaderParam("X-AIES-Workspace-Token") String token,@PathParam("workflowId") String workflowId,
        EvidenceInput input) throws Exception {
        authorize(token);
        try(var store=open()) {
            if(input==null) throw new BadRequestException("Missing evidence");
            store.recordEvidence(workflowId,input.experimentId(),input.metricValue(),input.sampleSize(),input.notes());
            return Response.status(201).entity(java.util.Map.of("workflowId",workflowId,"evidenceCount",store.evidenceCount(workflowId))).build();
        } catch(IllegalArgumentException e) {throw new BadRequestException("Invalid evidence");}
    }
    @GET @Path("/{workflowId}/metrics") public java.util.Map<String,Object> metrics(
        @HeaderParam("X-AIES-Workspace-Token") String token,@PathParam("workflowId") String workflowId) throws Exception {
        authorize(token);
        try(var store=open()) {
            var snapshot=store.find(workflowId).orElseThrow(NotFoundException::new);
            return java.util.Map.of("workflowId",workflowId,"status",snapshot.status(),"evidenceCount",store.evidenceCount(workflowId),
                "hypothesisCount",snapshot.plan().hypotheses().size(),"experimentCount",snapshot.plan().experiments().size());
        }
    }
}
