package dev.aies.control.execution;

import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GovernedInferenceBoundaryTest {
    private JsonObject valid() {
        return new JsonObject()
            .put("workflowId", "wf").put("taskId", "task")
            .put("attemptId", "attempt").put("specialistId", "software-engineer")
            .put("governedSpecialistVersion", "1.0")
            .put("capability", "software")
            .put("governedManifestSha256", "a".repeat(64))
            .put("governedRegistryRevision", 0L);
    }

    @Test void serverDerivesToolActionResource() {
        var body = valid().put("tool", "shell").put("action", "execute")
            .put("resource", "/etc/passwd").put("approved", true);
        var request = GovernedInferenceBoundary.request(body);
        assertEquals("ollama", request.tool());
        assertEquals("invoke-model", request.action());
        assertEquals("ollama:software-engineer", request.resource());
    }

    @Test void absentPolicyFailsClosedBeforeInference() throws Exception {
        var root = Files.createTempDirectory("aies-inference-boundary");
        assertThrows(SecurityException.class,
            () -> GovernedInferenceBoundary.require(valid(), root,
                new com.fasterxml.jackson.databind.ObjectMapper()));
    }

    @Test void missingIdentityFailsClosed() {
        var body = valid();
        body.remove("attemptId");
        assertThrows(IllegalArgumentException.class,
            () -> GovernedInferenceBoundary.request(body));
    }
}
