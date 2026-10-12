package dev.aies.control.routing;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Read-only preview; intentionally not wired to TaskRuntime execution. */
@Path("/api/v1/routing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class RoutingPreviewResource {
    @Inject CapabilityRouter router;
    public record PreviewRequest(String objective) {}
    public record ErrorResponse(String error) {}
    @POST @Path("/preview")
    public Response preview(PreviewRequest request) {
        try { return Response.ok(router.preview(request==null?null:request.objective())).build(); }
        catch(IllegalArgumentException e) { return Response.status(400).entity(new ErrorResponse(e.getMessage())).build(); }
    }
}
