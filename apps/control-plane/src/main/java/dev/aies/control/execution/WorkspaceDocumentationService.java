package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import io.vertx.core.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Explicitly authorized, bounded source-to-document operation. No shell, no arbitrary tools. */
public final class WorkspaceDocumentationService {
    private final Path root;
    private final Path dataRoot;
    private final OllamaSpecialistExecutor model;
    private final ObjectMapper mapper;
    private final dev.aies.control.specialists.GovernedSpecialistSelector governedGuard;
    private final dev.aies.control.specialists.GovernedSpecialistSelector.AssignmentSnapshot governedSnapshot;

    /** H02-D2C2f.3: trusted server-derived tool/action/resource; Event Bus never supplies grants. */
    private void authorizeGovernedTool(String workflowId, String taskId, String attemptId,
                                       String tool, String action, String resource) throws Exception {
        if (governedGuard == null) return; // frozen legacy execution path
        revalidateGoverned(workflowId, taskId, attemptId);
        var request = new GovernedToolResourcePolicy.Request(workflowId, taskId, attemptId,
            governedSnapshot.specialistId(), governedSnapshot.version(), tool, action, resource);
        // The policy revision is read from the immutable per-attempt operator-owned policy.
        // This is not a caller-supplied revision or a substitute for approval provenance.
        try (var policy = new GovernedToolResourcePolicyStore(
                dataRoot.resolve("governed-tool-policies.sqlite"));
             var execution = handoff()) {
            policy.requireAuthorizedCurrent(execution, new ExecutionHandoffStore.GovernedAttempt(
                workflowId, taskId, attemptId, governedSnapshot.specialistId(),
                governedSnapshot.version(), governedSnapshot.capability(),
                governedSnapshot.manifestSha256(), governedSnapshot.registryRevision()), request);
        }
    }

    private void revalidateGoverned(String workflowId, String taskId, String attemptId) throws Exception {
        if (governedGuard != null) {
            governedGuard.verifySnapshot(governedSnapshot);
            try (var store = handoff()) {
                store.requireGovernedAttempt(new ExecutionHandoffStore.GovernedAttempt(
                    workflowId, taskId, attemptId, governedSnapshot.specialistId(),
                    governedSnapshot.version(), governedSnapshot.capability(),
                    governedSnapshot.manifestSha256(), governedSnapshot.registryRevision()));
            }
        }
    }


    public WorkspaceDocumentationService(Path workspaceRoot, Path dataRoot, OllamaSpecialistExecutor model, ObjectMapper mapper) throws Exception {
        this(workspaceRoot, dataRoot, model, mapper, null, null);
    }

    public WorkspaceDocumentationService(Path workspaceRoot, Path dataRoot, OllamaSpecialistExecutor model,
        ObjectMapper mapper, dev.aies.control.specialists.GovernedSpecialistSelector guard,
        dev.aies.control.specialists.GovernedSpecialistSelector.AssignmentSnapshot snapshot) throws Exception {
        if ((guard == null) != (snapshot == null)) throw new IllegalArgumentException("Incomplete governed assignment");
        this.governedGuard = guard;
        this.governedSnapshot = snapshot;
        this.root = Objects.requireNonNull(workspaceRoot).toRealPath();
        if (!Files.isDirectory(root)) throw new IllegalArgumentException("Workspace must be a directory");
        this.dataRoot = Objects.requireNonNull(dataRoot).toAbsolutePath().normalize();
        this.model = Objects.requireNonNull(model);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** sourcePath is relative to workspace root. Output is always under .aies/generated-docs. */
    public JsonObject generate(JsonObject input) throws Exception {
        if (input == null) throw new IllegalArgumentException("Missing request");
        String workflowId = input.getString("workflowId");
        String taskId = input.getString("taskId");
        String attemptId = input.getString("attemptId");
        CommunicationContracts.id(workflowId);
        CommunicationContracts.id(taskId);
        CommunicationContracts.id(attemptId);
        // An Event Bus boolean is not an authorization. Verify the persisted grant and lease below.
        String sourcePath = input.getString("sourcePath");
        if (sourcePath == null || sourcePath.isBlank() || sourcePath.length() > 500) throw new IllegalArgumentException("Invalid source path");
        Path relative = Path.of(sourcePath);
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw new SecurityException("Path escape");
        Path source = root.resolve(relative).normalize();
        if (!source.startsWith(root) || !source.toString().endsWith(".java") || !Files.isRegularFile(source) || Files.isSymbolicLink(source))
            throw new SecurityException("Only regular Java sources allowed");
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(root)) throw new SecurityException("Source escapes workspace");
        if (Files.size(realSource) > 32_768) throw new IllegalArgumentException("Source too large");
        // A governed attempt cannot read source before an exact operator-owned grant.
        authorizeGovernedTool(workflowId, taskId, attemptId,
            "workspace-documentation", "read-source", root.relativize(realSource).toString());
        String javaSource = Files.readString(realSource, StandardCharsets.UTF_8);
        if (javaSource.isBlank()) throw new IllegalArgumentException("Empty source");
        String sourceSha = sha256(javaSource.getBytes(StandardCharsets.UTF_8));
        String approvedSourceSha = input.getString("approvedSourceSha256");
        if (approvedSourceSha == null || !sourceSha.equals(approvedSourceSha))
            throw new SecurityException("Source changed since authorization");
        revalidateGoverned(workflowId,taskId,attemptId);
        // Fence before any model request and replay durable artifacts for the same live attempt.
        try (var store = handoff()) {
            store.requireActiveDocumentation(workflowId,taskId,attemptId,sourcePath,sourceSha);
            var prior=store.effect(workflowId,taskId,attemptId);
            if(prior.isPresent() && "ARTIFACT_RECORDED".equals(prior.get().phase()))
                return replay(workflowId,taskId,attemptId,sourcePath,sourceSha,prior.get());
            if(prior.isEmpty())store.recordEffect(workflowId,taskId,attemptId,"PREPARED",null,null,null);
        }
        // If a prior attempt wrote a file but crashed before artifact append, recover it without a second model call.
        Path expectedOutput=root.resolve(".aies/generated-docs/").resolve(workflowId+"-"+taskId+"-"+attemptId+".md");
        if(Files.exists(expectedOutput,LinkOption.NOFOLLOW_LINKS)) {
            requirePublicationAdmission(workflowId,taskId,attemptId);
            return recoverWritten(workflowId,taskId,attemptId,sourcePath,sourceSha,expectedOutput);
        }
        Path staged=expectedOutput.resolveSibling(expectedOutput.getFileName().toString()+".staging");
        if(Files.exists(staged,LinkOption.NOFOLLOW_LINKS)) {
            requirePublicationAdmission(workflowId,taskId,attemptId);
            if(Files.isSymbolicLink(staged) || !Files.isRegularFile(staged,LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("Unsafe staging file");
            try(var store=handoff()) {
                var effect=store.effect(workflowId,taskId,attemptId).orElseThrow(
                    () -> new SecurityException("Unjournaled staging file"));
                if(!"FILE_WRITTEN".equals(effect.phase()) ||
                    !root.relativize(expectedOutput).toString().equals(effect.documentPath()) ||
                    !sha256(Files.readAllBytes(staged)).equals(effect.documentSha256()))
                    throw new SecurityException("Staging hash or journal mismatch");
                store.requireActiveDocumentation(workflowId,taskId,attemptId,sourcePath,sourceSha);
            }
            Files.move(staged,expectedOutput,StandardCopyOption.ATOMIC_MOVE);
            return recoverWritten(workflowId,taskId,attemptId,sourcePath,sourceSha,expectedOutput);
        }
        String objective = "Document this Java source faithfully as Markdown. Include responsibilities, public API and limitations. " +
            "Do not invent tests or execution results. Source SHA-256: " + sourceSha + "\n" + TechnicalSpecialistGuidance.documentation() + "\n" + javaSource;
        if (objective.length() > 4096) throw new IllegalArgumentException("Source exceeds model prompt budget");
        var request = new JsonObject().put("workflowId", workflowId).put("taskId", taskId)
            .put("attemptId", attemptId).put("specialistId", "software-engineer")
            .put("capability", "software").put("objective", objective);
        try(var store=handoff()) {store.requireActiveDocumentation(workflowId,taskId,attemptId,sourcePath,sourceSha);}
        revalidateGoverned(workflowId,taskId,attemptId);
        // Check immediately before reserving the external model invocation.
        authorizeGovernedTool(workflowId, taskId, attemptId,
            "workspace-documentation", "invoke-model", "ollama:software-engineer");
        // Atomic durable reservation: concurrent Event Bus deliveries must not invoke
        // the model twice for the same attempt. A crash after reservation is ambiguous.
        try(var store=handoff()) {
            if(!store.reserveModelInvocation(workflowId,taskId,attemptId,sourcePath,sourceSha))
                throw new IllegalStateException("Model invocation already reserved; reconcile before retry");
        }
        revalidateGoverned(workflowId,taskId,attemptId);
        var response = model.execute(request);
        if (!attemptId.equals(response.getString("attemptId"))) throw new SecurityException("Attempt mismatch");
        String markdown = response.getString("content");
        if (markdown == null || markdown.isBlank() || markdown.length() > 131072) throw new IllegalStateException("Invalid document");
        try(var store=handoff()) {store.requireActiveDocumentation(workflowId,taskId,attemptId,sourcePath,sourceSha);}
        revalidateGoverned(workflowId,taskId,attemptId);
        // H02-D2C2f.12h: recheck the durable policy after HTTP.
        // A revocation while the model was running must deny publication.
        authorizeGovernedTool(workflowId, taskId, attemptId,
            "workspace-documentation", "invoke-model", "ollama:software-engineer");
        // 12k: admission is ordered with operator revocation in the policy SQLite writer.
        // This does not make SQLite and filesystem writes a single atomic transaction.
        if (governedGuard != null) {
            var publicationRequest = new GovernedToolResourcePolicy.Request(workflowId, taskId, attemptId,
                governedSnapshot.specialistId(), governedSnapshot.version(),
                "workspace-documentation", "invoke-model", "ollama:software-engineer");
            try (var policy = new GovernedToolResourcePolicyStore(dataRoot.resolve("governed-tool-policies.sqlite"))) {
                policy.admitPublication(publicationRequest);
            }
        }
        // Do not allow symlink-based writes in the reserved output tree.
        Path aies = root.resolve(".aies");
        if (Files.exists(aies, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(aies) || !Files.isDirectory(aies)))
            throw new SecurityException("Unsafe output directory");
        Files.createDirectories(aies);
        Path docs = aies.resolve("generated-docs");
        if (Files.exists(docs, LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(docs) || !Files.isDirectory(docs)))
            throw new SecurityException("Unsafe output directory");
        Files.createDirectories(docs);
        Path output = docs.resolve(workflowId + "-" + taskId + "-" + attemptId + ".md");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());
        // Journal the material hash before atomic publication; a staged file is
        // recoverable only if its digest matches the durable journal.
        Path staging = docs.resolve(output.getFileName().toString() + ".staging");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Unjournaled or conflicting staging file");
        String documentSha = sha256(markdown.getBytes(StandardCharsets.UTF_8));
        Files.writeString(staging, markdown, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try(var store=handoff()) {
            store.recordEffect(workflowId,taskId,attemptId,"FILE_WRITTEN",null,
                root.relativize(output).toString(),documentSha);
        }
        Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        var artifact = CommunicationContracts.validate(new CommunicationContracts.Artifact(
            CommunicationContracts.VERSION, workflowId, taskId, "software-engineer", "execution-result", "text/markdown",
            markdown, java.util.List.of(WorkspaceMaterialEvidence.reference(root.relativize(output).toString(), documentSha))));
        String artifactId;
        try (var communication = new CommunicationStore(dataRoot.resolve("communication.sqlite"), mapper)) {
            artifactId = communication.appendIdempotent("execution-"+attemptId+"-artifact", "ARTIFACT", workflowId, taskId, artifact).id();
        }
        revalidateGoverned(workflowId,taskId,attemptId);
        try(var store=handoff()) {store.recordEffect(workflowId,taskId,attemptId,"ARTIFACT_RECORDED",artifactId,root.relativize(output).toString(),documentSha);}
        return new JsonObject().put("status", "EVIDENCE_PENDING").put("workflowId", workflowId)
            .put("taskId", taskId).put("attemptId", attemptId).put("sourcePath", root.relativize(realSource).toString())
            .put("sourceSha256", sourceSha).put("documentPath", root.relativize(output).toString())
            .put("documentSha256", documentSha).put("artifactRecordId", artifactId)
            .put("verification", "FILE_HASH_VERIFIED_NOT_AUDITED");
    }
    /** Recovery/replay may only materialize evidence for an already admitted governed attempt. */
    private void requirePublicationAdmission(String workflowId, String taskId, String attemptId) throws Exception {
        if (governedGuard == null) return; // Legacy mode remains unchanged.
        try (var policy = new GovernedToolResourcePolicyStore(dataRoot.resolve("governed-tool-policies.sqlite"))) {
            if (!policy.publicationAdmitted(workflowId, taskId, attemptId))
                throw new SecurityException("Missing durable publication admission for recovery");
        }
    }

    private ExecutionHandoffStore handoff() throws Exception {
        return new ExecutionHandoffStore(dataRoot.resolve("execution-handoff.sqlite"),mapper);
    }
    private JsonObject recoverWritten(String workflowId,String taskId,String attemptId,String sourcePath,String sourceSha,Path output) throws Exception {
        revalidateGoverned(workflowId,taskId,attemptId);
        requirePublicationAdmission(workflowId,taskId,attemptId);
        if(Files.isSymbolicLink(output) || !output.toRealPath().startsWith(root))throw new SecurityException("Unsafe recovery file");
        var bytes=Files.readAllBytes(output);
        if(bytes.length==0 || bytes.length>131072)throw new SecurityException("Invalid recovery content");
        String documentSha=sha256(bytes);
        try(var store=handoff()) {
            store.requireActiveDocumentation(workflowId,taskId,attemptId,sourcePath,sourceSha);
            var effect=store.effect(workflowId,taskId,attemptId).orElseThrow();
            if(!"FILE_WRITTEN".equals(effect.phase()) || !root.relativize(output).toString().equals(effect.documentPath())
                || !documentSha.equals(effect.documentSha256()))throw new SecurityException("Unjournaled or altered recovery file");
        }
        var artifact=CommunicationContracts.validate(new CommunicationContracts.Artifact(
            CommunicationContracts.VERSION,workflowId,taskId,"software-engineer","execution-result","text/markdown",
            new String(bytes,StandardCharsets.UTF_8),java.util.List.of(WorkspaceMaterialEvidence.reference(root.relativize(output).toString(),documentSha))));
        String artifactId;
        try(var communication=new CommunicationStore(dataRoot.resolve("communication.sqlite"),mapper)) {
            artifactId=communication.appendIdempotent("execution-"+attemptId+"-artifact", "ARTIFACT",workflowId,taskId,artifact).id();
        }
        revalidateGoverned(workflowId,taskId,attemptId);
        try(var store=handoff()) {store.recordEffect(workflowId,taskId,attemptId,"ARTIFACT_RECORDED",artifactId,root.relativize(output).toString(),documentSha);}
        return new JsonObject().put("status","EVIDENCE_PENDING").put("workflowId",workflowId)
            .put("taskId",taskId).put("attemptId",attemptId).put("sourcePath",sourcePath)
            .put("sourceSha256",sourceSha).put("documentPath",root.relativize(output).toString())
            .put("documentSha256",documentSha).put("artifactRecordId",artifactId)
            .put("verification","FILE_HASH_VERIFIED_NOT_AUDITED");
    }
    private JsonObject replay(String workflowId,String taskId,String attemptId,String sourcePath,String sourceSha,ExecutionHandoffStore.Effect effect) throws Exception {
        requirePublicationAdmission(workflowId,taskId,attemptId);
        if(effect.artifactId()==null || effect.documentPath()==null || effect.documentSha256()==null)
            throw new SecurityException("Incomplete effect journal");
        Path output=root.resolve(effect.documentPath()).normalize();
        if(!output.startsWith(root) || Files.isSymbolicLink(output) || !Files.isRegularFile(output)
            || !sha256(Files.readAllBytes(output)).equals(effect.documentSha256()))throw new SecurityException("Material evidence changed");
        try(var communication=new CommunicationStore(dataRoot.resolve("communication.sqlite"),mapper)) {
            ExecutionEvidenceVerifier.verify(communication,workflowId,taskId,"artifact://communication/"+effect.artifactId());
        }
        return new JsonObject().put("status","EVIDENCE_PENDING").put("workflowId",workflowId)
            .put("taskId",taskId).put("attemptId",attemptId).put("sourcePath",sourcePath)
            .put("sourceSha256",sourceSha).put("documentPath",effect.documentPath())
            .put("documentSha256",effect.documentSha256()).put("artifactRecordId",effect.artifactId())
            .put("verification","FILE_HASH_VERIFIED_NOT_AUDITED").put("replayed",true);
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
