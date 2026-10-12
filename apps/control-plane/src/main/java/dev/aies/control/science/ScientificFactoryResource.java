package dev.aies.control.science;

import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.concurrent.TimeUnit;

/** Operator-facing API only. All internal work is dispatched via Vert.x Event Bus. */
@Path("/api/v1/scientific-workflows") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON) @Blocking
public class ScientificFactoryResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean scienceEnabled;
    @ConfigProperty(name="aies.factory.enabled",defaultValue="false") boolean factoryEnabled;
    private void auth(String token){authorizer.authorize(scienceEnabled && factoryEnabled,token);}
    private JsonObject request(String address,JsonObject payload) throws Exception {
        try {return vertx.eventBus().<JsonObject>request(address,payload,new DeliveryOptions().setSendTimeout(15000))
            .toCompletionStage().toCompletableFuture().get(16,TimeUnit.SECONDS).body();}
        catch(java.util.concurrent.TimeoutException e){throw new ServiceUnavailableException("Factory timed out");}
        catch(java.util.concurrent.ExecutionException e){throw new BadRequestException("Factory command rejected");}
    }
    @POST @Path("/{workflowId}/factory/prepare")
    public JsonObject prepare(@HeaderParam("X-AIES-Workspace-Token")String token,@PathParam("workflowId")String id) throws Exception {
        auth(token);ResearchEvidence.validId(id);
        return request(ScientificFactoryEventBus.PREPARE,new JsonObject().put("workflowId",id));
    }
    @GET @Path("/factory/records/{recordId}/delivery")
    public JsonObject delivery(@HeaderParam("X-AIES-Workspace-Token")String token,@PathParam("recordId")String id) throws Exception {
        auth(token);ResearchEvidence.validId(id);
        return request(ScientificFactoryEventBus.DELIVERY,new JsonObject().put("recordId",id));
    }
    @POST @Path("/products/business/evaluate")
    public JsonObject business(@HeaderParam("X-AIES-Workspace-Token")String token,ScientificProductMetrics.Business input) throws Exception {
        auth(token);ScientificProductMetrics.business(input);
        return request(ScientificFactoryEventBus.BUSINESS,JsonObject.mapFrom(input));
    }
    @POST @Path("/products/trading/evaluate")
    public JsonObject trading(@HeaderParam("X-AIES-Workspace-Token")String token,ScientificProductMetrics.Trading input) throws Exception {
        auth(token);ScientificProductMetrics.trading(input);
        return request(ScientificFactoryEventBus.TRADING,JsonObject.mapFrom(input));
    }
}
