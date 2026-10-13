package dev.aies.control.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Explicit operator-only revision creation. No implicit approval or execution. */
@Path("/api/v1/planning") @Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON) @Blocking
public class PlanningRevisionResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.planning.enabled",defaultValue="false") boolean planningEnabled;
    @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean communicationsEnabled;
    @ConfigProperty(name="aies.specialists.enabled",defaultValue="false") boolean specialistsEnabled;
    @ConfigProperty(name="aies.planning.revisions.enabled",defaultValue="false") boolean revisionsEnabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String root;
    private void auth(String token){
        authorizer.authorize(planningEnabled&&communicationsEnabled&&specialistsEnabled&&revisionsEnabled,token);
    }
    private PlanningRevisionStore open()throws Exception{
        return new PlanningRevisionStore(java.nio.file.Path.of(root).resolve("planning.sqlite"),mapper);
    }
    @POST @Path("/workflows/{parentId}/revisions")
    public Response revise(@HeaderParam("X-AIES-Workspace-Token") String token,
                           @PathParam("parentId") String parentId,
                           PlanningRevisionStore.Request request)throws Exception {
        auth(token);
        try(var store=open()){
            var result=store.revise(parentId,request);
            return Response.status(result.replayed()?200:201).entity(result).build();
        }catch(IllegalArgumentException e){throw new BadRequestException("Invalid revision request");}
        catch(IllegalStateException|java.sql.SQLException e){throw new ClientErrorException("Revision conflict or ineligible parent",409);}
    }
    @GET @Path("/revisions/{requestId}")
    public PlanningRevisionStore.Result get(@HeaderParam("X-AIES-Workspace-Token")String token,
                                            @PathParam("requestId")String requestId)throws Exception{
        auth(token);
        try(var store=open()){return store.find(requestId).orElseThrow(NotFoundException::new);}
        catch(IllegalArgumentException e){throw new BadRequestException("Invalid request ID");}
    }
}
