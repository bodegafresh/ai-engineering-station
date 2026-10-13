package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Ollama inference AND independent real Ollama semantic audit.
 * No fabricated audit decisions. No filesystem grants or shell execution by agents.
 * Does not claim HTTP intake/operator approval or material acceptance.
 */
class F43G2OllamaAuditLiveTest {
    @TempDir Path root;

    @Test
    @EnabledIfEnvironmentVariable(named = "AIES_F43G2_LIVE", matches = "true")
    void realModelAndSemanticAuditDoNotBypassQualityGate() throws Exception {
        String url = System.getenv().getOrDefault("AIES_MODEL_BASE_URL", "http://127.0.0.1:11434");
        String model = System.getenv().getOrDefault("AIES_MODEL_NAME", "qwen3:4b");
        ObjectMapper mapper = new ObjectMapper();
        Vertx vertx = Vertx.vertx();
        try {
            var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper, url, model, 90);
            var specialist = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, msg ->
                vertx.<JsonObject>executeBlocking(() -> executor.execute(msg.body()), false)
                    .onSuccess(msg::reply).onFailure(err -> msg.fail(503, "Inference failed")));
            var auditor = vertx.eventBus().<JsonObject>localConsumer(CommunicationEventBus.AUDIT_REQUEST, msg ->
                vertx.<JsonObject>executeBlocking(() -> {
                    String recordId = msg.body().getString("recordId");
                    try (var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
                        var evidence = comm.find(recordId).orElseThrow();
                        assertEquals("ARTIFACT", evidence.kind());
                        var verdict = SemanticAuditPolicy.review(evidence, mapper, url, model, 90);
                        var decision = verdict.approved()
                            ? CommunicationContracts.AuditDecision.APPROVED
                            : CommunicationContracts.AuditDecision.BLOCKED;
                        var audit = CommunicationContracts.validate(new CommunicationContracts.Audit(
                            CommunicationContracts.VERSION, evidence.workflowId(), evidence.id(),
                            "live-semantic-auditor", decision, 1, 1, verdict.findings(), List.of()));
                        var saved = comm.append("AUDIT", evidence.workflowId(), evidence.taskId(), audit);
                        return new JsonObject().put("decision", decision.name()).put("auditRecordId", saved.id());
                    }
                }, false).onSuccess(msg::reply).onFailure(err -> msg.fail(503, "Audit failed")));
            await(specialist); await(auditor);
            try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                assertTrue(handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a"))));
            }
            long started = System.nanoTime();
            var first = new ExecutionWorkerEngine(vertx, mapper, root, 1, 240, 120).runOne("wf-execution");
            assertEquals("EVIDENCE_PENDING_VERIFICATION", first.getString("status"), first.encodePrettily());
            String artifactId = first.getString("artifactRecordId");
            assertNotNull(artifactId);
            var pending = new ExecutionAcceptanceManifest(root, mapper).inspect("wf-execution");
            assertEquals("INCOMPLETE", pending.acceptanceStatus());
            var result = new ExecutionQualityGate(vertx, mapper, root, 120).review("wf-execution", "a");
            String decision = result.getString("decision");
            assertTrue("APPROVED".equals(decision) || "BLOCKED".equals(decision), result.encodePrettily());
            var manifest = new ExecutionAcceptanceManifest(root, mapper).inspect("wf-execution");
            assertEquals("APPROVED".equals(decision) ? "VERIFIED" : "INCOMPLETE", manifest.acceptanceStatus());
            try (var reopened = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
                 var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
                assertEquals("APPROVED".equals(decision) ? "COMPLETED" : "EVIDENCE_PENDING",
                    reopened.list("wf-execution").getFirst().status());
                var audit = comm.find(result.getString("auditRecordId")).orElseThrow();
                assertEquals("AUDIT", audit.kind());
                assertEquals(artifactId, ((CommunicationContracts.Audit) audit.payload()).subjectId());
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("F43G2_LIVE observed_at=%s model=%s elapsed_ms=%d artifact_id=%s audit_id=%s decision=%s acceptance=%s%n",
                Instant.now(), model, ms, artifactId, result.getString("auditRecordId"), decision, manifest.acceptanceStatus());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
    private static void await(io.vertx.core.eventbus.MessageConsumer<JsonObject> consumer) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        consumer.completionHandler(ar -> { if (ar.succeeded()) ready.complete(null); else ready.completeExceptionally(ar.cause()); });
        ready.get(5, TimeUnit.SECONDS);
    }
}
