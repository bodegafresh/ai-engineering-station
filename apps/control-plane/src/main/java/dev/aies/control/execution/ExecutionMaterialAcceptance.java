package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Strict material acceptance is separate from canonical audit acceptance.
 * This read-only check never promotes a workflow or changes durable state.
 * Call only from a blocking worker or @Blocking REST boundary.
 */
public final class ExecutionMaterialAcceptance {
    public record MaterialCheck(String taskId, String status, String detail) {}
    public record Result(String workflowId, String status, List<MaterialCheck> checks,
                         List<String> limitations) {}

    private final Path dataRoot;
    private final Path workspaceRoot;
    private final ObjectMapper mapper;

    public ExecutionMaterialAcceptance(Path dataRoot, Path workspaceRoot, ObjectMapper mapper) {
        this.dataRoot = Objects.requireNonNull(dataRoot);
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot);
        this.mapper = Objects.requireNonNull(mapper);
    }

    /** Fail closed: no material reference is NOT a verified material file. */
    public Result inspect(String workflowId) throws Exception {
        var canonical = new ExecutionAcceptanceManifest(dataRoot, mapper).inspect(workflowId);
        var checks = new ArrayList<MaterialCheck>();
        boolean allVerified = "VERIFIED".equals(canonical.acceptanceStatus());
        try (var communication = new CommunicationStore(dataRoot.resolve("communication.sqlite"), mapper)) {
            for (var task : canonical.tasks()) {
                if (!"COMPLETED".equals(task.status()) || task.artifactId() == null) {
                    checks.add(new MaterialCheck(task.taskId(), "INCOMPLETE", "Task not canonically completed"));
                    allVerified = false;
                    continue;
                }
                var record = ExecutionEvidenceVerifier.verify(communication, workflowId, task.taskId(),
                    "artifact://communication/" + task.artifactId());
                if (!(record.payload() instanceof CommunicationContracts.Artifact artifact))
                    throw new SecurityException("Unexpected artifact payload");
                long refs = artifact.sourceRefs().stream()
                    .filter(ref -> ref.startsWith(WorkspaceMaterialEvidence.PREFIX)).count();
                if (refs == 0) {
                    checks.add(new MaterialCheck(task.taskId(), "NOT_APPLICABLE", "No material reference recorded"));
                    allVerified = false;
                    continue;
                }
                // Existing verifier validates every material ref, its content and its path.
                // A tampered file is a hard security failure, never a VERIFIED result.
                WorkspaceMaterialEvidence.verify(workspaceRoot, artifact);
                checks.add(new MaterialCheck(task.taskId(), "VERIFIED", "Material content and SHA-256 match"));
            }
        }
        return new Result(workflowId, allVerified && !checks.isEmpty() ? "MATERIAL_VERIFIED" : "INCOMPLETE",
            List.copyOf(checks), List.of(
                "Read-only inspection across SQLite and filesystem; not an atomic snapshot",
                "Material verification is point-in-time; files can change after inspection",
                "Canonical audit approval is required independently",
                "No model version, latency, or external outcome is asserted"));
    }
}
