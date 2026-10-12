package dev.aies.control.execution;

import dev.aies.control.patch.WorkspaceChangeService;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Operator-only verification API. No model-facing tool or arbitrary command endpoint. */
@Path("/api/v1/workspace-verifications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class WorkspaceVerificationResource {
    public record Request(String profile) {}
    @ConfigProperty(name="aies.workspace-verification.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.workspace-changes.token") Optional<String> token;
    @ConfigProperty(name="aies.teams.projects-root",defaultValue="./projects") String projectsRoot;
    @ConfigProperty(name="aies.workspace-changes.sessions-root",defaultValue="./data/workspace-sessions") String sessionsRoot;
    @ConfigProperty(name="aies.workspace-verification.image") Optional<String> image;
    private void authorize(String supplied) {
        String expected=token.orElse("");
        if(!enabled || expected.length()<32 || supplied==null ||
                !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) throw new NotFoundException();
    }
    @POST @Path("/{id}")
    public VerificationEvidence verify(@HeaderParam("X-AIES-Workspace-Token") String secret,
                                       @PathParam("id") String id,Request request) throws Exception {
        authorize(secret);
        if(request==null || !"java-gradle".equals(request.profile())) throw new BadRequestException();
        if(image.isEmpty()) throw new ServiceUnavailableException("Verification image not configured");
        try(var changes=new WorkspaceChangeService(java.nio.file.Path.of(projectsRoot),java.nio.file.Path.of(sessionsRoot));
            var evidence=new VerificationEvidenceRepository(java.nio.file.Path.of(sessionsRoot).resolve("verification-evidence.sqlite"))) {
            return new WorkspaceVerificationService(changes,new DockerVerificationRunner(image.get(),180),evidence)
                    .verify(id,request.profile());
        } catch(NoSuchElementException e) {throw new NotFoundException();}
        catch(IllegalStateException e) {throw new ClientErrorException("Proposal not applied",409);}
    }
    @GET @Path("/{id}")
    public List<VerificationEvidence> history(@HeaderParam("X-AIES-Workspace-Token") String secret,
                                              @PathParam("id") String id) throws Exception {
        authorize(secret);
        try(var changes=new WorkspaceChangeService(java.nio.file.Path.of(projectsRoot),java.nio.file.Path.of(sessionsRoot));
            var evidence=new VerificationEvidenceRepository(java.nio.file.Path.of(sessionsRoot).resolve("verification-evidence.sqlite"))) {
            return new WorkspaceVerificationService(changes,new DockerVerificationRunner(
                    image.orElseThrow(()->new ServiceUnavailableException("Verification image not configured")),180),
                    evidence).history(id);
        } catch(NoSuchElementException e) {throw new NotFoundException();}
    }
}
