package dev.aies.control.memory;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Explicitly opt-in local memory API; never open without a dedicated secret. */
@Path("/api/v1/memory")
@ApplicationScoped
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MemoryResource {
    @ConfigProperty(name="aies.memory.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.memory.token") Optional<String> token;
    @ConfigProperty(name="aies.data.dir", defaultValue="./data") String dataDir;
    private volatile SqliteProjectMemory store;

    public record Write(String projectId, String kind, String content) {}
    public record Search(String projectId, String query, int limit, int maxEstimatedTokens) {}
    public record SearchResult(List<SqliteProjectMemory.Entry> entries, MemoryContextBudget.Selection context) {}

    private void authorize(String provided) {
        String expectedToken = token.orElse("");

        if (!enabled
                || expectedToken.length() < 32
                || provided == null
                || !MessageDigest.isEqual(
                        expectedToken.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            throw new NotFoundException();
        }
    }
    private SqliteProjectMemory store() throws Exception {
        if (store == null) synchronized (this) {
            if (store == null) {
                java.nio.file.Path dir = java.nio.file.Path.of(dataDir).toAbsolutePath().normalize();
                Files.createDirectories(dir);
                store = new SqliteProjectMemory(dir.resolve("project-memory-v017.sqlite"), 1000);
            }
        }
        return store;
    }
    @POST @Path("/remember")
    public Response remember(@HeaderParam("X-AIES-Memory-Token") String secret, Write body) throws Exception {
        authorize(secret);
        if (body == null) throw new BadRequestException();
        try { return Response.status(201).entity(store().remember(body.projectId(),body.kind(),body.content())).build(); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid memory input"); }
    }
    @POST @Path("/retrieve")
    public SearchResult retrieve(@HeaderParam("X-AIES-Memory-Token") String secret, Search body) throws Exception {
        authorize(secret);
        if (body == null) throw new BadRequestException();
        try {
            var entries = store().retrieve(body.projectId(),body.query(),body.limit());
            return new SearchResult(entries, new MemoryContextBudget().assemble(entries,body.maxEstimatedTokens()));
        } catch (IllegalArgumentException e) { throw new BadRequestException("Invalid memory search"); }
    }
    @DELETE @Path("/{projectId}")
    public java.util.Map<String,Integer> delete(@HeaderParam("X-AIES-Memory-Token") String secret,
                                                @PathParam("projectId") String projectId) throws Exception {
        authorize(secret);
        try { return java.util.Map.of("deleted",store().deleteProject(projectId)); }
        catch (IllegalArgumentException e) { throw new BadRequestException("Invalid project ID"); }
    }
    @PreDestroy void shutdown() throws SQLException { if (store != null) store.close(); }
}
