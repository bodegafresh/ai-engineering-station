package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import java.util.Objects;

/** Verifies the immutable, checksum-validated canonical artifact, not an external URL. */
public final class ExecutionEvidenceVerifier {
    private ExecutionEvidenceVerifier() {}

    public static CommunicationStore.StoredRecord verify(
            CommunicationStore communication, String workflowId, String taskId, String evidenceRef) throws Exception {
        CommunicationContracts.id(workflowId);
        CommunicationContracts.id(taskId);
        Objects.requireNonNull(communication);
        if (evidenceRef == null || !evidenceRef.startsWith("artifact://communication/"))
            throw new IllegalArgumentException("Unsupported evidence reference");
        String recordId = evidenceRef.substring("artifact://communication/".length());
        CommunicationContracts.id(recordId);
        var record = communication.find(recordId).orElseThrow(() -> new IllegalArgumentException("Evidence not found"));
        if (!"ARTIFACT".equals(record.kind()) || !workflowId.equals(record.workflowId())
                || !taskId.equals(record.taskId()))
            throw new SecurityException("Evidence ownership mismatch");
        if (!(record.payload() instanceof CommunicationContracts.Artifact artifact))
            throw new SecurityException("Unexpected evidence payload");
        CommunicationContracts.validate(artifact);
        if (!workflowId.equals(artifact.workflowId()) || !taskId.equals(artifact.taskId())
                || !"execution-result".equals(artifact.kind()))
            throw new SecurityException("Invalid execution evidence");
        if (artifact.content().isBlank()) throw new IllegalArgumentException("Empty evidence");
        return record;
    }
}
