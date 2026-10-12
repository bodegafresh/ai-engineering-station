package dev.aies.control.patch;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Dedicated human-operated patch endpoint. Never called by a model or team executor. */
@Path("/api/v1/patches")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ControlledPatchResource {
    public record Prepare(String projectId, String path, String replacement) {}
    public record Approval(String originalSha256, String proposedSha256) {}
    @ConfigProperty(name="aies.patches.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.patches.token") Optional<String> token;
    @ConfigProperty(name="aies.teams.projects-root", defaultValue="./projects") String projectsRoot;
    private volatile ControlledPatchStore store;

    private void authorize(String supplied) {
        String expected = token.orElse("");
        if (!enabled || expected.length() < 32 || supplied == null ||
            !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            throw new NotFoundException();
    }
    private ControlledPatchStore store() throws Exception {
        if (store == null) synchronized (this) {
            if (store == null) store = new ControlledPatchStore(java.nio.file.Path.of(projectsRoot));
        }
        return store;
    }
    @POST @Path("/prepare")
    public Response prepare(@HeaderParam("X-AIES-Patch-Token") String secret, Prepare input) throws Exception {
        authorize(secret);
        if (input == null) throw new BadRequestException("Missing body");
        try { return Response.status(201).entity(store().prepare(input.projectId(), input.path(), input.replacement())).build(); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid proposal"); }
    }
    @GET @Path("/{id}")
    public ControlledPatchStore.Proposal inspect(@HeaderParam("X-AIES-Patch-Token") String secret,
            @PathParam("id") String id) throws Exception {
        authorize(secret);
        try { return store().inspect(id); }
        catch (NoSuchElementException e) { throw new NotFoundException(); }
    }
    @POST @Path("/{id}/approve")
    public ControlledPatchStore.Proposal approve(@HeaderParam("X-AIES-Patch-Token") String secret,
            @PathParam("id") String id, Approval input) throws Exception {
        authorize(secret);
        if (input == null) throw new BadRequestException("Missing approval hashes");
        try { return store().approve(id, input.originalSha256(), input.proposedSha256()); }
        catch (NoSuchElementException e) { throw new NotFoundException(); }
        catch (IllegalStateException e) { throw new ClientErrorException("Approval conflict", 409); }
    }
    @POST @Path("/{id}/apply")
    public ControlledPatchStore.Proposal apply(@HeaderParam("X-AIES-Patch-Token") String secret,
            @PathParam("id") String id) throws Exception {
        authorize(secret);
        try { return store().apply(id); }
        catch (NoSuchElementException e) { throw new NotFoundException(); }
        catch (SecurityException e) { throw new ForbiddenException("Approval required"); }
        catch (IllegalStateException e) { throw new ClientErrorException("Original changed or proposal expired", 409); }
    }
}
