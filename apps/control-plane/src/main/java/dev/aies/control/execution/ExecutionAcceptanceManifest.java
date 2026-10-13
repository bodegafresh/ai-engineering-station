package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only acceptance snapshot. No task transitions or model calls. */
public final class ExecutionAcceptanceManifest {
    public record TaskEvidence(String taskId, String specialistId, String status, int attempts,
                               String artifactId, String artifactSha256, String auditId,
                               String auditSha256, String auditDecision, String artifactCreatedAt,
                               String auditCreatedAt) {}
    public record Manifest(String schemaVersion, String workflowId, String acceptanceStatus,
                           List<TaskEvidence> tasks, List<String> limitations) {}
    private final Path root;
    private final ObjectMapper mapper;

    public ExecutionAcceptanceManifest(Path root, ObjectMapper mapper) {
        this.root = Objects.requireNonNull(root);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public Manifest inspect(String workflowId) throws Exception {
        CommunicationContracts.id(workflowId);
        try (var execution = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
             var communication = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
            // Distinguish an unknown workflow from an empty one.
            execution.plan(workflowId);
            var items = new ArrayList<TaskEvidence>();
            boolean complete = true;
            for (var task : execution.list(workflowId)) {
                String artifactId = null, artifactSha = null, auditId = null, auditSha = null;
                String decision = null, artifactAt = null, auditAt = null;
                if (task.evidenceRef() != null) {
                    var artifact = ExecutionEvidenceVerifier.verify(communication, workflowId, task.taskId(), task.evidenceRef());
                    artifactId = artifact.id();
                    artifactSha = artifact.sha256();
                    artifactAt = artifact.createdAt();
                }
                if ("COMPLETED".equals(task.status())) {
                    auditId = execution.approvedAuditId(workflowId, task.taskId()).orElseThrow(
                        () -> new SecurityException("Completed task missing durable audit"));
                    var audit = communication.find(auditId).orElseThrow(
                        () -> new SecurityException("Approved audit record missing"));
                    if (!"AUDIT".equals(audit.kind()) || !workflowId.equals(audit.workflowId())
                            || !task.taskId().equals(audit.taskId())
                            || !(audit.payload() instanceof CommunicationContracts.Audit value)
                            || !Objects.equals(artifactId, value.subjectId())
                            || value.decision() != CommunicationContracts.AuditDecision.APPROVED)
                        throw new SecurityException("Approved audit does not match evidence");
                    auditSha = audit.sha256();
                    auditAt = audit.createdAt();
                    decision = value.decision().name();
                } else {
                    complete = false;
                }
                items.add(new TaskEvidence(task.taskId(), task.specialistId(), task.status(), task.attempts(),
                    artifactId, artifactSha, auditId, auditSha, decision, artifactAt, auditAt));
            }
            return new Manifest("1.0", workflowId, complete && !items.isEmpty() ? "VERIFIED" : "INCOMPLETE",
                List.copyOf(items), List.of(
                    "Read-only snapshot; not an atomic cross-database transaction",
                    "SHA-256 refers to immutable canonical record payload, not necessarily a current workspace file",
                    "Model version, timing and material file hashes are not available in this manifest version",
                    "VERIFIED confirms canonical audit linkage, not external real-world outcomes"));
        }
    }
}
