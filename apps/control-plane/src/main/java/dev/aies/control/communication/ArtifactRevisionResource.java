package dev.aies.control.communication;

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

/** Operator entry point only; internal revision is handled over Vert.x Event Bus. */
@Path("/api/v1/agent-communications") @Consumes(MediaType.APPLICATION_JSON) @Produces(MediaType.APPLICATION_JSON) @Blocking
public class ArtifactRevisionResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean enabled;
    public record RevisionInput(String content) {}
    @POST @Path("/records/{recordId}/revisions")
    public JsonObject revise(@HeaderParam("X-AIES-Workspace-Token") String token,
                             @PathParam("recordId") String recordId,RevisionInput input) throws Exception {
        authorizer.authorize(enabled,token);
        CommunicationContracts.id(recordId);
        if(input==null || input.content()==null) throw new BadRequestException("Missing revision content");
        try {
            return vertx.eventBus().<JsonObject>request(ArtifactRevisionEventBus.REVISE,
                new JsonObject().put("parentRecordId",recordId).put("content",input.content()),
                new DeliveryOptions().setSendTimeout(15000)).toCompletionStage().toCompletableFuture()
                .get(16,TimeUnit.SECONDS).body();
        } catch(java.util.concurrent.TimeoutException e){throw new ServiceUnavailableException("Revision timed out");}
        catch(java.util.concurrent.ExecutionException e){throw new BadRequestException("Revision rejected");}
    }
}
