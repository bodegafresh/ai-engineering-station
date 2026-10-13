package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionCapabilityAuthorizationTest {
    private static final String SHA = "a".repeat(64);
    @Test void acceptsBoundJavaInput() {
        var a = new ExecutionCapabilityAuthorization("wf", "task", "workspace-documentation", "src/A.java", SHA);
        assertEquals("src/A.java", a.sourcePath());
    }
    @Test void rejectsEscapesAndUnsupportedCapabilities() {
        assertThrows(SecurityException.class, () -> new ExecutionCapabilityAuthorization("wf", "task", "workspace-documentation", "../A.java", SHA));
        assertThrows(SecurityException.class, () -> new ExecutionCapabilityAuthorization("wf", "task", "workspace-documentation", "/tmp/A.java", SHA));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapabilityAuthorization("wf", "task", "shell", "src/A.java", SHA));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionCapabilityAuthorization("wf", "task", "workspace-documentation", "src/A.java", "bad"));
    }
}
