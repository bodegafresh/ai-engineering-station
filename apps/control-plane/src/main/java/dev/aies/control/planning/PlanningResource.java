package dev.aies.control.planning;

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
import java.util.concurrent.TimeUnit;

/** Operator-only REST ingress; internal orchestration is Vert.x Event Bus. */
@Path("/api/v1/planning") @Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON) @Blocking
public class PlanningResource {
 @Inject WorkspaceOperatorAuthorizer authorizer;
 @Inject ObjectMapper mapper;
 @Inject Vertx vertx;
 @ConfigProperty(name="aies.planning.enabled",defaultValue="false") boolean enabled;
 @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String root;
 @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean communicationEnabled;
 @ConfigProperty(name="aies.specialists.enabled",defaultValue="false") boolean specialistsEnabled;
 private void auth(String token){authorizer.authorize(enabled&&communicationEnabled&&specialistsEnabled,token);}
 private PlanningStore open()throws Exception{return new PlanningStore(java.nio.file.Path.of(root).resolve("planning.sqlite"),mapper);}
 @POST @Path("/workflows")
 public Response create(@HeaderParam("X-AIES-Workspace-Token")String token,PlanningContracts.Intake input)throws Exception {
  auth(token);
  try(var store=open()){return Response.status(201).entity(store.create(input)).build();}
  catch(java.sql.SQLException e){if(e.getMessage()!=null&&e.getMessage().contains("UNIQUE"))
   throw new ClientErrorException("Duplicate workflow",409);throw e;}
  catch(IllegalArgumentException|NullPointerException e){throw new BadRequestException("Invalid planning intake");}
 }
 @GET @Path("/workflows/{id}")
 public PlanningStore.Snapshot get(@HeaderParam("X-AIES-Workspace-Token")String token,
                                    @PathParam("id")String id)throws Exception{
  auth(token);try(var store=open()){return store.find(id).orElseThrow(NotFoundException::new);}
  catch(IllegalArgumentException e){throw new BadRequestException("Invalid workflow ID");}
 }
 @POST @Path("/workflows/{id}/prepare")
 public JsonObject prepare(@HeaderParam("X-AIES-Workspace-Token")String token,
                           @PathParam("id")String id)throws Exception{
  auth(token);PlanningContracts.id(id);
  try{
   return vertx.eventBus().<JsonObject>request(PlanningEventBus.AUDIT,
     new JsonObject().put("workflowId",id),new DeliveryOptions().setSendTimeout(125000))
     .toCompletionStage().toCompletableFuture().get(130,TimeUnit.SECONDS).body();
  }catch(java.util.concurrent.TimeoutException e){throw new ServiceUnavailableException("Planning audit timed out");}
   catch(java.util.concurrent.ExecutionException e){throw new BadRequestException("Planning preparation rejected");}
 }
}
