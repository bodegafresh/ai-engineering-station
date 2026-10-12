package dev.aies.control.execution;

import dev.aies.control.patch.WorkspaceChangeService;
import dev.aies.control.security.WorkspaceOperatorAuthorizer;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Operator-only verification API; profiles and images are trusted server configuration. */
@Path("/api/v1/workspace-verifications")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Blocking
public class WorkspaceVerificationResource {
    public record Request(String profile) {}
    @Inject WorkspaceOperatorAuthorizer authorizer;
    @ConfigProperty(name="aies.workspace-verification.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.teams.projects-root",defaultValue="./projects") String projectsRoot;
    @ConfigProperty(name="aies.workspace-changes.sessions-root",defaultValue="./data/workspace-sessions") String sessionsRoot;
    @ConfigProperty(name="aies.workspace-verification.image") Optional<String> legacyImage;
    private final VerificationProfileRegistry registry=new VerificationProfileRegistry();
    private void authorize(String supplied) { authorizer.authorize(enabled,supplied); }
    private String configuredImage(String profile) {
        String key=registry.require(profile).configKey();
        Optional<String> configured=ConfigProvider.getConfig().getOptionalValue(key,String.class);
        if(configured.isPresent() && !configured.get().isBlank()) return configured.get();
        if("java-gradle".equals(profile)) return legacyImage.orElseThrow(()->new ServiceUnavailableException("Verification image not configured"));
        throw new ServiceUnavailableException("Verification image not configured");
    }
    @GET @Path("/profiles")
    public List<String> profiles(@HeaderParam("X-AIES-Workspace-Token") String secret) {
        authorize(secret); return registry.ids();
    }
    @POST @Path("/{id}")
    public VerificationEvidence verify(@HeaderParam("X-AIES-Workspace-Token") String secret,
                                       @PathParam("id") String id,Request request) throws Exception {
        authorize(secret);
        if(request==null) throw new BadRequestException();
        try {registry.require(request.profile());} catch(IllegalArgumentException e) {throw new BadRequestException("Unsupported verification profile");}
        String image=configuredImage(request.profile());
        try(var changes=new WorkspaceChangeService(java.nio.file.Path.of(projectsRoot),java.nio.file.Path.of(sessionsRoot));
            var evidence=new VerificationEvidenceRepository(java.nio.file.Path.of(sessionsRoot).resolve("verification-evidence.sqlite"))) {
            return new WorkspaceVerificationService(changes,new DockerVerificationRunner(image,180,registry),evidence)
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
            changes.inspect(id); return evidence.history(id);
        } catch(NoSuchElementException e) {throw new NotFoundException();}
    }
}
