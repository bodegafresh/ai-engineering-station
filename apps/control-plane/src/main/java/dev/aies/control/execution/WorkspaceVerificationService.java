package dev.aies.control.execution;

import dev.aies.control.patch.WorkspaceChangeService;
import java.nio.file.Path;
import java.util.*;
import dev.aies.control.patch.PatchDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Application service, with runner and repository as explicit dependencies. */
public final class WorkspaceVerificationService {
    private final WorkspaceChangeService changes;
    private final VerificationRunner runner;
    private final VerificationEvidenceRepository evidence;
    public WorkspaceVerificationService(WorkspaceChangeService changes,VerificationRunner runner,
                                        VerificationEvidenceRepository evidence) {
        this.changes=Objects.requireNonNull(changes);
        this.runner=Objects.requireNonNull(runner);
        this.evidence=Objects.requireNonNull(evidence);
    }
    public VerificationEvidence verify(
            String proposalId,
            String profile
    ) throws Exception {

        Path path = changes.sessionPath(proposalId);

        VerificationEvidence result;

        try {
            result = runner.verify(path, profile);
        } catch (IllegalArgumentException e) {
            String message = switch (e.getMessage()) {
                case "Gradle wrapper missing" ->
                        "Gradle wrapper missing";
                case "Unsupported verification profile" ->
                        "Unsupported verification profile";
                default ->
                        "Verification configuration error";
            };

            byte[] output = message.getBytes(StandardCharsets.UTF_8);

            result = new VerificationEvidence(
                    "ERROR",
                    profile,
                    -1,
                    false,
                    PatchDigest.sha256(output),
                    message,
                    Instant.now()
            );
        }

        evidence.append(proposalId, result);

        return result;
    }
    public List<VerificationEvidence> history(String proposalId) throws Exception {
        changes.inspect(proposalId);
        return evidence.history(proposalId);
    }
}
