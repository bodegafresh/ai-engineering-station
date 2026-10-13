package dev.aies.control.execution;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealSpecialistPolicyTest {
    private JsonObject valid() {
        return new JsonObject().put("workflowId","wf-1").put("taskId","task-1")
            .put("attemptId","lease-1").put("specialistId","researcher")
            .put("capability","research").put("objective","Document component behavior")
            .put("given","A Java class").put("when","Reviewing it").put("then","Write Markdown");
    }
    @Test void buildsBoundedPrompt() {
        String prompt = RealSpecialistPolicy.prompt(valid());
        assertTrue(prompt.contains("Document component behavior"));
        assertTrue(prompt.contains("Do not invent citations"));
    }
    @Test void rejectsUnknownOrMismatchedSpecialists() {
        assertThrows(IllegalArgumentException.class, () -> RealSpecialistPolicy.prompt(valid().put("specialistId","invented")));
        assertThrows(IllegalArgumentException.class, () -> RealSpecialistPolicy.prompt(valid().put("capability","software")));
    }
    @Test void rejectsOversizedAndMissingInputs() {
        assertThrows(IllegalArgumentException.class, () -> RealSpecialistPolicy.prompt(valid().copy().put("attemptId", (String) null)));
        assertThrows(IllegalArgumentException.class, () -> RealSpecialistPolicy.prompt(valid().put("objective","x".repeat(4097))));
    }
    @Test void rejectsNonLocalModelUrls() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var client = java.net.http.HttpClient.newHttpClient();
        assertThrows(IllegalArgumentException.class, () -> new OllamaSpecialistExecutor(client, mapper,"https://example.com","qwen3:4b",20));
        assertThrows(IllegalArgumentException.class, () -> new OllamaSpecialistExecutor(client, mapper,"http://127.0.0.1:11434/evil","qwen3:4b",20));
    }
}
