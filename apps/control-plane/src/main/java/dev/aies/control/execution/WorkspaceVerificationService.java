package dev.aies.control.execution;

import dev.aies.control.patch.ApprovedWorktreeIntegrityVerifier;
import dev.aies.control.patch.ApprovedWorktreeSnapshot;
import dev.aies.control.patch.PatchDigest;
import dev.aies.control.patch.WorkspaceChangeService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Coordinates approved immutable snapshots, sandbox execution and durable evidence. */
public final class WorkspaceVerificationService {
    private final WorkspaceChangeService changes;
    private final VerificationRunner runner;
    private final VerificationEvidenceRepository evidence;
    private final ApprovedWorktreeIntegrityVerifier integrity;

    public WorkspaceVerificationService(WorkspaceChangeService changes, VerificationRunner runner,
                                        VerificationEvidenceRepository evidence) {
        this(changes, runner, evidence, new ApprovedWorktreeIntegrityVerifier());
    }
    public WorkspaceVerificationService(WorkspaceChangeService changes, VerificationRunner runner,
                                        VerificationEvidenceRepository evidence,
                                        ApprovedWorktreeIntegrityVerifier integrity) {
        this.changes = Objects.requireNonNull(changes);
        this.runner = Objects.requireNonNull(runner);
        this.evidence = Objects.requireNonNull(evidence);
        this.integrity = Objects.requireNonNull(integrity);
    }
    public VerificationEvidence verify(String proposalId, String profile) throws Exception {
        Path path = changes.sessionPath(proposalId);
        var proposal = changes.inspect(proposalId);
        VerificationEvidence result;
        try (var snapshot = ApprovedWorktreeSnapshot.create(path, proposal, integrity)) {
            try {
                result = runner.verify(snapshot.path(), profile);
            } catch (IllegalArgumentException e) {
                String message = switch (String.valueOf(e.getMessage())) {
                    case "Gradle wrapper missing" -> "Gradle wrapper missing";
                    case "Required project manifest missing" -> "Required project manifest missing";
                    case "Unsupported verification profile" -> "Unsupported verification profile";
                    default -> "Verification configuration error";
                };
                result = error(profile, message);
            }
        } catch (SecurityException e) {
            // Do not execute unapproved content. Persist a sanitized error only.
            result = error(profile, "Approved worktree integrity rejected");
        }
        evidence.append(proposalId, result);
        return result;
    }
    private static VerificationEvidence error(String profile, String message) {
        byte[] output = message.getBytes(StandardCharsets.UTF_8);
        return new VerificationEvidence("ERROR", profile, -1, false,
                PatchDigest.sha256(output), message, Instant.now());
    }
    public List<VerificationEvidence> history(String proposalId) throws Exception {
        changes.inspect(proposalId);
        return evidence.history(proposalId);
    }
}
