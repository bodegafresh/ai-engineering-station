package dev.aies.control.specialists;

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

/** Operator entry only; all specialist-to-specialist routing uses the Event Bus. */
@Path("/api/v1/specialists")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class SpecialistResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.specialists.enabled",defaultValue="false") boolean enabled;
    @POST @Path("/select")
    public JsonObject select(@HeaderParam("X-AIES-Workspace-Token") String token, JsonObject request) throws Exception {
        authorizer.authorize(enabled,token);
        if(request == null) throw new BadRequestException("Missing selection request");
        try {
            return vertx.eventBus().<JsonObject>request(SpecialistEventBus.SELECT,request,
                new DeliveryOptions().setSendTimeout(5000))
                .toCompletionStage().toCompletableFuture().get(6,TimeUnit.SECONDS).body();
        } catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e) {
            throw new ServiceUnavailableException("Specialist selection unavailable");
        }
    }
}
