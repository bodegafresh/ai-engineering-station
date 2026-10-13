package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Explicit quality transition. Structural audit alone is not proof of external outcomes. */
public final class ExecutionQualityGate {
    private final Vertx vertx;
    private final ObjectMapper mapper;
    private final Path root;
    private final int timeoutSeconds;
    private Path workspaceRoot;

    public ExecutionQualityGate(Vertx vertx, ObjectMapper mapper, Path root, int timeoutSeconds) {
        if (timeoutSeconds < 1 || timeoutSeconds > 180) throw new IllegalArgumentException("Invalid audit timeout");
        this.vertx = java.util.Objects.requireNonNull(vertx);
        this.mapper = java.util.Objects.requireNonNull(mapper);
        this.root = java.util.Objects.requireNonNull(root);
        this.timeoutSeconds = timeoutSeconds;
        this.workspaceRoot = null;
    }

    public ExecutionQualityGate(Vertx vertx, ObjectMapper mapper, Path root, int timeoutSeconds, Path workspaceRoot) {
        this(vertx, mapper, root, timeoutSeconds);
        this.workspaceRoot = java.util.Objects.requireNonNull(workspaceRoot);
    }

    private void verifyMaterial(CommunicationStore.StoredRecord record) throws Exception {
        if (!(record.payload() instanceof CommunicationContracts.Artifact artifact)) return;
        boolean material = artifact.sourceRefs().stream().anyMatch(ref -> ref.startsWith(WorkspaceMaterialEvidence.PREFIX));
        if (material) {
            if (workspaceRoot == null) throw new SecurityException("Material verification not configured");
            WorkspaceMaterialEvidence.verify(workspaceRoot, artifact);
        }
    }

    public JsonObject review(String workflowId, String taskId) throws Exception {
        CommunicationContracts.id(workflowId);
        CommunicationContracts.id(taskId);
        String evidenceRef;
        String artifactId;
        try (var execution = handoff(); var communication = communications()) {
            var work = execution.list(workflowId).stream().filter(w -> taskId.equals(w.taskId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown task"));
            if ("COMPLETED".equals(work.status()))
                return new JsonObject().put("status", "COMPLETED").put("replayed", true);
            if (!"EVIDENCE_PENDING".equals(work.status()))
                throw new IllegalStateException("Task not awaiting evidence verification");
            evidenceRef = work.evidenceRef();
            var evidence = ExecutionEvidenceVerifier.verify(communication, workflowId, taskId, evidenceRef);
            verifyMaterial(evidence);
            artifactId = evidence.id();
        }
        var response = vertx.eventBus().<JsonObject>request(CommunicationEventBus.AUDIT_REQUEST,
            new JsonObject().put("recordId", artifactId),
            new DeliveryOptions().setSendTimeout(timeoutSeconds * 1000L))
            .toCompletionStage().toCompletableFuture().get(timeoutSeconds + 2L, TimeUnit.SECONDS).body();
        String decision = response.getString("decision");
        String auditId = response.getString("auditRecordId");
        if (decision == null || auditId == null) throw new IllegalStateException("Incomplete audit response");
        try (var execution = handoff(); var communication = communications()) {
            // Revalidate immutable records after asynchronous audit, before state transition.
            verifyMaterial(ExecutionEvidenceVerifier.verify(communication, workflowId, taskId, evidenceRef));
            var auditRecord = communication.find(auditId).orElseThrow();
            if (!"AUDIT".equals(auditRecord.kind()) || !workflowId.equals(auditRecord.workflowId())
                || !taskId.equals(auditRecord.taskId())) throw new SecurityException("Audit ownership mismatch");
            if (!(auditRecord.payload() instanceof CommunicationContracts.Audit audit)
                || !artifactId.equals(audit.subjectId()) || !decision.equals(audit.decision().name()))
                throw new SecurityException("Audit decision mismatch");
            if (!"APPROVED".equals(decision))
                return new JsonObject().put("status", "EVIDENCE_PENDING")
                    .put("decision", decision).put("auditRecordId", auditId);
            boolean changed = execution.approveVerifiedEvidence(workflowId, taskId, evidenceRef, auditId, communication, workspaceRoot);
            return new JsonObject().put("status", changed ? "COMPLETED" : "CONFLICT")
                .put("decision", decision).put("auditRecordId", auditId);
        }
    }

    private ExecutionHandoffStore handoff() throws Exception {
        return new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
    }
    private CommunicationStore communications() throws Exception {
        return new CommunicationStore(root.resolve("communication.sqlite"), mapper);
    }
}
