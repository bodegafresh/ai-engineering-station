package dev.aies.control.execution;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Preview only. Explicitly does not execute models or tools. */
@Path("/api/v1/execution")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class PromptCompositionResource {
    @Inject SpecialistPromptComposer composer;
    public record Request(String objective) {}
    public record Preview(String specialistId, String prompt, java.util.List<String> reviewers,
                          java.util.List<String> caveats, boolean routed, boolean executable) {}
    public record Error(String error) {}
    @POST @Path("/preview")
    public Response preview(Request request) {
        try {
            var result = composer.compose(request == null ? null : request.objective());
            return Response.ok(new Preview(result.specialistId(), result.prompt(), result.reviewers(),
                    result.caveats(), result.routed(), false)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(new Error(e.getMessage())).build();
        }
    }
}
