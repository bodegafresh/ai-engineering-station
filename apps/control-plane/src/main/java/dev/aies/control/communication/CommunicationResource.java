package dev.aies.control.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** P0.4 operator-only contract intake. Does not invoke models, tools or mutate projects. */
@Path("/api/v1/agent-communications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class CommunicationResource {
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private void authorize(String token) {authorizer.authorize(enabled,token);}
    private CommunicationStore open() throws Exception {
        return new CommunicationStore(java.nio.file.Path.of(dataRoot).resolve("communication.sqlite"),mapper);
    }
    @POST @Path("/messages")
    public Response message(@HeaderParam("X-AIES-Workspace-Token") String token,CommunicationContracts.Message request) throws Exception {
        authorize(token);
        try {
            var m=CommunicationContracts.validate(request);
            try(var store=open()) {return Response.status(201).entity(store.append("MESSAGE",m.workflowId(),m.taskId(),m)).build();}
        } catch(IllegalArgumentException|NullPointerException e) {throw new BadRequestException("Invalid communication contract");}
    }
    @POST @Path("/artifacts")
    public Response artifact(@HeaderParam("X-AIES-Workspace-Token") String token,CommunicationContracts.Artifact request) throws Exception {
        authorize(token);
        try {
            var a=CommunicationContracts.validate(request);
            try(var store=open()) {return Response.status(201).entity(store.append("ARTIFACT",a.workflowId(),a.taskId(),a)).build();}
        } catch(IllegalArgumentException|NullPointerException e) {throw new BadRequestException("Invalid artifact contract");}
    }
    @POST @Path("/audits")
    public Response audit(@HeaderParam("X-AIES-Workspace-Token") String token,CommunicationContracts.Audit request) throws Exception {
        authorize(token);
        try {
            var a=CommunicationContracts.validate(request);
            try(var store=open()) {
                var subject=store.find(a.subjectId()).orElseThrow(()->new NotFoundException("Subject not found"));
                if(!subject.workflowId().equals(a.workflowId()) || "AUDIT".equals(subject.kind()))
                    throw new ClientErrorException("Invalid audit subject",409);
                return Response.status(201).entity(store.append("AUDIT",a.workflowId(),subject.taskId(),a)).build();
            }
        } catch(IllegalArgumentException|NullPointerException e) {throw new BadRequestException("Invalid audit contract");}
    }
    @GET @Path("/workflows/{workflowId}/records")
    public List<CommunicationStore.StoredRecord> list(@HeaderParam("X-AIES-Workspace-Token") String token,
              @PathParam("workflowId") String workflowId) throws Exception {
        authorize(token);
        try(var store=open()) {return store.list(workflowId);}
        catch(IllegalArgumentException e) {throw new BadRequestException("Invalid workflow ID");}
    }
    @GET @Path("/records/{id}")
    public CommunicationStore.StoredRecord get(@HeaderParam("X-AIES-Workspace-Token") String token,
              @PathParam("id") String id) throws Exception {
        authorize(token);
        try(var store=open()) {return store.find(id).orElseThrow(NotFoundException::new);}
        catch(IllegalArgumentException e) {throw new BadRequestException("Invalid record ID");}
    }
}
