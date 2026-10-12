package dev.aies.control.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Human-operated workspace change API. Agent output is untrusted and never auto-approved. */
@Path("/api/v1/workspace-changes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceChangeResource {
    public record Prepare(String projectId,List<WorkspaceChangeService.Change> changes) {}
    public record AgentPrepare(String projectId,String agentOutput) {}
    public record Approval(String fingerprint,String reviewer) {}
    @Inject ObjectMapper mapper;
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @ConfigProperty(name="aies.workspace-changes.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.teams.projects-root",defaultValue="./projects") String projectsRoot;
    @ConfigProperty(name="aies.workspace-changes.sessions-root",defaultValue="./data/workspace-sessions") String sessionsRoot;
    private volatile WorkspaceChangeService service;
    private void authorize(String supplied) { authorizer.authorize(enabled, supplied); }
    private WorkspaceChangeService service() throws Exception {
        if(service==null) synchronized(this) {
            if(service==null) service=new WorkspaceChangeService(java.nio.file.Path.of(projectsRoot),
                    java.nio.file.Path.of(sessionsRoot));
        }
        return service;
    }
    @POST @Path("/prepare")
    public Response prepare(@HeaderParam("X-AIES-Workspace-Token") String secret,Prepare body) throws Exception {
        authorize(secret);
        if(body==null) throw new BadRequestException();
        try {return Response.status(201).entity(service().prepare(body.projectId(),body.changes())).build();}
        catch(IllegalArgumentException e) {throw new BadRequestException("Invalid proposal");}
        catch(IllegalStateException e) {throw new ClientErrorException("Conflict",409);}
    }
    @POST @Path("/prepare-from-agent-output")
    public Response prepareFromAgentOutput(@HeaderParam("X-AIES-Workspace-Token") String secret,AgentPrepare body) throws Exception {
        authorize(secret);
        if(body==null) throw new BadRequestException();
        try {
            var changes=new AgentChangeProposalParser(mapper).parse(body.agentOutput());
            return Response.status(201).entity(service().prepare(body.projectId(),changes)).build();
        } catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BadRequestException("Invalid agent proposal");
        } catch(IllegalStateException e) {throw new ClientErrorException("Conflict",409);}
    }
    @GET @Path("/{id}")
    public WorkspaceChangeService.Proposal inspect(@HeaderParam("X-AIES-Workspace-Token") String secret,
            @PathParam("id") String id) throws Exception {
        authorize(secret);
        try {return service().inspect(id);}
        catch(NoSuchElementException e) {throw new NotFoundException();}
    }
    @POST @Path("/{id}/approve")
    public WorkspaceChangeService.Proposal approve(@HeaderParam("X-AIES-Workspace-Token") String secret,
            @PathParam("id") String id,Approval body) throws Exception {
        authorize(secret);
        if(body==null) throw new BadRequestException();
        try {return service().approve(id,body.fingerprint(),body.reviewer());}
        catch(SecurityException | IllegalStateException e) {throw new ClientErrorException("Approval rejected",409);}
        catch(NoSuchElementException e) {throw new NotFoundException();}
    }
    @POST @Path("/{id}/apply")
    public WorkspaceChangeService.Proposal apply(@HeaderParam("X-AIES-Workspace-Token") String secret,
            @PathParam("id") String id) throws Exception {
        authorize(secret);
        try {return service().apply(id);}
        catch(NoSuchElementException e) {throw new NotFoundException();}
        catch(SecurityException e) {throw new ForbiddenException();}
        catch(IllegalStateException e) {throw new ClientErrorException("Conflict",409);}
    }
    @PreDestroy void close() throws Exception {if(service!=null)service.close();}
}
