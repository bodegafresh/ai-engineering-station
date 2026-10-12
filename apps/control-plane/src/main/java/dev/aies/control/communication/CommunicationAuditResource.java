package dev.aies.control.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Operator API only. Internal audit requests are always routed through Vert.x Event Bus. */
@Path("/api/v1/agent-communication-audits")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class CommunicationAuditResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private void authorize(String token) {authorizer.authorize(enabled,token);}
    private CommunicationStore open() throws Exception {
        return new CommunicationStore(java.nio.file.Path.of(dataRoot).resolve("communication.sqlite"),mapper);
    }
    @POST @Path("/{recordId}/evaluate")
    public JsonObject evaluate(@HeaderParam("X-AIES-Workspace-Token") String token,
                               @PathParam("recordId") String recordId) throws Exception {
        authorize(token);
        try(var store=open()) {
            var subject=store.find(recordId).orElseThrow(NotFoundException::new);
            if("AUDIT".equals(subject.kind())) throw new BadRequestException("Invalid audit subject");
        } catch(IllegalArgumentException e) {throw new BadRequestException("Invalid record ID");}
        try {
            return vertx.eventBus().<JsonObject>request(CommunicationEventBus.AUDIT_REQUEST,
                new JsonObject().put("recordId",recordId),new DeliveryOptions().setSendTimeout(15000))
                .toCompletionStage().toCompletableFuture().get(16,TimeUnit.SECONDS).body();
        } catch(java.util.concurrent.TimeoutException e) {throw new ServiceUnavailableException("Audit timed out");}
        catch(java.util.concurrent.ExecutionException e) {throw new ServiceUnavailableException("Audit unavailable");}
    }
    @GET @Path("/{recordId}/delivery")
    public JsonObject delivery(@HeaderParam("X-AIES-Workspace-Token") String token,
                               @PathParam("recordId") String recordId) throws Exception {
        authorize(token);
        try(var store=open()) {
            var subject=store.find(recordId).orElseThrow(NotFoundException::new);
            if("AUDIT".equals(subject.kind())) throw new BadRequestException("Invalid delivery subject");
            List<CommunicationContracts.Audit> audits=store.list(subject.workflowId()).stream()
                .filter(r -> "AUDIT".equals(r.kind()))
                .map(r -> (CommunicationContracts.Audit)r.payload())
                .filter(a -> a.subjectId().equals(recordId)).toList();
            String decision=audits.isEmpty()?"PENDING":audits.getLast().decision().name();
            return new JsonObject().put("recordId",recordId).put("decision",decision)
                .put("deliverable","APPROVED".equals(decision));
        } catch(IllegalArgumentException e) {throw new BadRequestException("Invalid record ID");}
    }
}
