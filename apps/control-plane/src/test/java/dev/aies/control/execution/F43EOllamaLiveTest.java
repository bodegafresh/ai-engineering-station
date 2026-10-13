package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in live Ollama smoke; no fabricated audit and no filesystem tool grants. */
class F43EOllamaLiveTest {
    @TempDir Path root;

    @Test
    @EnabledIfEnvironmentVariable(named = "AIES_F43E_LIVE", matches = "true")
    void actualLocalOllamaRespondsThroughVertxAndStagesDurableUnverifiedEvidence() throws Exception {
        String url = System.getenv().getOrDefault("AIES_MODEL_BASE_URL", "http://127.0.0.1:11434");
        String model = System.getenv().getOrDefault("AIES_MODEL_NAME", "qwen3:4b");
        ObjectMapper mapper = new ObjectMapper();
        Vertx vertx = Vertx.vertx();
        try {
            var adapter = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper, url, model, 90);
            var consumer = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, msg ->
                vertx.<JsonObject>executeBlocking(() -> adapter.execute(msg.body()), false)
                    .onSuccess(msg::reply).onFailure(e -> msg.fail(503, "Local inference failed")));
            java.util.concurrent.CompletableFuture<Void> ready = new java.util.concurrent.CompletableFuture<>(); consumer.completionHandler(ar -> { if (ar.succeeded()) ready.complete(null); else ready.completeExceptionally(ar.cause()); }); ready.get(5, TimeUnit.SECONDS);
            try (var store = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            }
            long start = System.nanoTime();
            var result = new ExecutionWorkerEngine(vertx, mapper, root, 1, 180, 120).runOne("wf-execution");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertEquals("EVIDENCE_PENDING_VERIFICATION", result.getString("status"), result.encodePrettily());
            String recordId = result.getString("artifactRecordId");
            try (var store = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
                 var communication = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
                assertEquals("EVIDENCE_PENDING", store.list("wf-execution").getFirst().status());
                assertNotNull(ExecutionEvidenceVerifier.verify(communication, "wf-execution", "a",
                    "artifact://communication/" + recordId));
            }
            assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper)
                .inspect("wf-execution").acceptanceStatus());
            System.out.printf("F43E_LIVE observed_at=%s model=%s elapsed_ms=%d status=EVIDENCE_PENDING artifact_record_id=%s acceptance=INCOMPLETE%n",
                Instant.now(), model, elapsedMs, recordId);
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
