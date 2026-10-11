package dev.aies.control;
import dev.aies.domain.Task;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import io.smallrye.common.annotation.Blocking;
import java.net.URI;
import java.util.UUID;

@Path("/api/v1/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class TaskResource {
    @Inject TaskRuntime runtime;
    public record SubmitRequest(String objective) {}
    public record ErrorResponse(String error) {}
    @POST public Response create(SubmitRequest request) {
        if(request==null||request.objective()==null||request.objective().isBlank()||request.objective().length()>4000)
            return Response.status(400).entity(new ErrorResponse("objective must contain 1-4000 characters")).build();
        Task task=runtime.service().submit(request.objective());
        runtime.wakeup();
        return Response.created(URI.create("/api/v1/tasks/"+task.id())).entity(task).build();
    }
    @GET @Path("/{id}") public Response get(@PathParam("id") String id) {
        UUID uuid;
        try{uuid=UUID.fromString(id);}catch(IllegalArgumentException e){return Response.status(400).build();}
        return runtime.service().find(uuid).map(t->Response.ok(t).build()).orElseGet(()->Response.status(404).build());
    }
    @POST @Path("/{id}/cancel") public Response cancel(@PathParam("id") String id) {
        UUID uuid;
        try{uuid=UUID.fromString(id);}catch(IllegalArgumentException e){return Response.status(400).build();}
        return runtime.service().cancel(uuid)?Response.noContent().build():Response.status(409).build();
    }
}
