package dev.aies.control.execution;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GovernedDocumentationToolBoundaryTest {
    @Test void missingOperatorGrantIsDeniedBeforeAnyExecution() throws Exception {
        var directory = Files.createTempDirectory("aies-doc-policy-boundary");
        try (var policy = new GovernedToolResourcePolicyStore(directory.resolve("policy.sqlite"))) {
            var attempt = new ExecutionHandoffStore.GovernedAttempt(
                "wf", "task", "attempt", "software-engineer", "1.0", "software",
                "a".repeat(64), 0);
            var request = new GovernedToolResourcePolicy.Request(
                "wf", "task", "attempt", "software-engineer", "1.0",
                "workspace-documentation", "read-source", "src/A.java");
            assertThrows(SecurityException.class,
                () -> policy.requireAuthorizedCurrent(null, attempt, request));
        }
    }
}
