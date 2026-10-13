package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import java.nio.file.Path;
import java.util.Objects;

/** Immutable operator-approved input; never derived from an LLM response. */
public record ExecutionCapabilityAuthorization(String workflowId, String taskId,
        String capability, String sourcePath, String sourceSha256) {
    public ExecutionCapabilityAuthorization {
        CommunicationContracts.id(workflowId);
        CommunicationContracts.id(taskId);
        if (!"workspace-documentation".equals(capability))
            throw new IllegalArgumentException("Unsupported capability");
        Objects.requireNonNull(sourcePath);
        if (sourcePath.isBlank() || sourcePath.length() > 500) throw new IllegalArgumentException("Invalid source path");
        Path path = Path.of(sourcePath);
        if (path.isAbsolute() || path.normalize().startsWith("..") || !sourcePath.endsWith(".java"))
            throw new SecurityException("Unsafe source path");
        if (sourceSha256 == null || !sourceSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid source SHA-256");
    }
}
