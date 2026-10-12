package dev.aies.control.science;

import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.TimeUnit;

/** Operator-facing API only. Commands and retrospective processing use Vert.x Event Bus. */
@Path("/api/v1/scientific-workflows") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @Blocking
public class ScientificResearchResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean enabled;
    private void auth(String token){authorizer.authorize(enabled,token);}
    private JsonObject send(String address,JsonObject payload) throws Exception {
        try {return vertx.eventBus().<JsonObject>request(address,payload,new DeliveryOptions().setSendTimeout(10000))
                .toCompletionStage().toCompletableFuture().get(11,TimeUnit.SECONDS).body();}
        catch(java.util.concurrent.TimeoutException e){throw new ServiceUnavailableException("Event Bus timed out");}
        catch(java.util.concurrent.ExecutionException e){throw new BadRequestException("Scientific command rejected");}
    }
    @POST @Path("/{workflowId}/sources") public Response source(@HeaderParam("X-AIES-Workspace-Token")String token,
        @PathParam("workflowId")String workflowId,ResearchEvidence.Source input) throws Exception {
        auth(token);if(input==null||!workflowId.equals(input.workflowId()))throw new BadRequestException("Workflow mismatch");
        ResearchEvidence.validate(input);
        return Response.status(201).entity(send(ScientificResearchEventBus.SOURCE,new JsonObject().put("source",JsonObject.mapFrom(input)))).build();
    }
    public record ObservationInput(String experimentId,boolean success,double cost,double durationSeconds,String notes) {}
    @POST @Path("/{workflowId}/observations") public Response observation(@HeaderParam("X-AIES-Workspace-Token")String token,
        @PathParam("workflowId")String workflowId,ObservationInput input) throws Exception {
        auth(token);ResearchEvidence.validId(workflowId);if(input==null)throw new BadRequestException("Missing observation");
        return Response.status(201).entity(send(ScientificResearchEventBus.OBSERVATION,new JsonObject()
            .put("workflowId",workflowId).put("experimentId",input.experimentId()).put("success",input.success())
            .put("cost",input.cost()).put("durationSeconds",input.durationSeconds()).put("notes",input.notes()))).build();
    }
    @GET @Path("/{workflowId}/retrospective") public JsonObject retrospective(@HeaderParam("X-AIES-Workspace-Token")String token,
        @PathParam("workflowId")String workflowId) throws Exception {
        auth(token);ResearchEvidence.validId(workflowId);
        return send(ScientificResearchEventBus.RETROSPECTIVE,new JsonObject().put("workflowId",workflowId));
    }
}
