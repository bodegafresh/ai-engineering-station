package dev.aies.control.execution;

import java.nio.file.Path;

/** Port for independently verifiable workspace checks. */
public interface VerificationRunner {
    VerificationEvidence verify(Path worktree, String profile) throws Exception;
}
