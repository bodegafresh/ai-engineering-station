package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationEventBus;
import dev.aies.control.communication.CommunicationStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** F4.3-G: HTTP fixture + actual adapter + Event Bus + SQLite + quality gate.
 * Audit decisions are deterministic test fixtures, NOT a real semantic auditor or Ollama.
 */
class F43GQualityGateIntegrationTest {
    @TempDir Path root;

    @Test void blockedAuditCannotAdvanceDagButExactApprovalCanAndSurvivesReopen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/api/generate", exchange -> {
            httpCalls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"response\":\"Fixture output, not audited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        http.start();
        Vertx vertx = Vertx.vertx();
        try {
            var adapter = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper,
                "http://127.0.0.1:" + http.getAddress().getPort(), "qwen3:4b", 10);
            var specialist = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE,
                msg -> vertx.<JsonObject>executeBlocking(() -> adapter.execute(msg.body()), false)
                    .onSuccess(msg::reply).onFailure(err -> msg.fail(503, "Fixture failure")));
            var decision = new java.util.concurrent.atomic.AtomicReference<>(CommunicationContracts.AuditDecision.BLOCKED);
            var auditor = vertx.eventBus().<JsonObject>localConsumer(CommunicationEventBus.AUDIT_REQUEST, msg -> {
                vertx.<JsonObject>executeBlocking(() -> {
                    String recordId = msg.body().getString("recordId");
                    try (var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
                        var subject = comm.find(recordId).orElseThrow();
                        var audit = CommunicationContracts.validate(new CommunicationContracts.Audit(
                            CommunicationContracts.VERSION, subject.workflowId(), subject.id(),
                            "fixture-auditor", decision.get(), 1, 3,
                            decision.get() == CommunicationContracts.AuditDecision.APPROVED
                                ? List.of() : List.of("Fixture rejects this evidence"), List.of()));
                        var persisted = comm.append("AUDIT", subject.workflowId(), subject.taskId(), audit);
                        return new JsonObject().put("decision", decision.get().name())
                            .put("auditRecordId", persisted.id());
                    }
                }, false).onSuccess(msg::reply).onFailure(err -> msg.fail(503, "Fixture audit failure"));
            });
            awaitRegistration(specialist);
            awaitRegistration(auditor);
            try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                assertTrue(handoff.importPlan(ExecutionGraphTest.plan(
                    ExecutionGraphTest.task("b", "a"), ExecutionGraphTest.task("a"))));
            }
            var worker = new ExecutionWorkerEngine(vertx, mapper, root, 3, 60, 10);
            var gate = new ExecutionQualityGate(vertx, mapper, root, 10);
            var first = worker.runOne("wf-execution");
            assertEquals("a", first.getString("taskId"));
            assertEquals("EVIDENCE_PENDING_VERIFICATION", first.getString("status"));
            assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper)
                .inspect("wf-execution").acceptanceStatus());
            var blocked = gate.review("wf-execution", "a");
            assertEquals("BLOCKED", blocked.getString("decision"));
            assertEquals("EVIDENCE_PENDING", blocked.getString("status"));
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));
            assertEquals(1, httpCalls.get());
            decision.set(CommunicationContracts.AuditDecision.APPROVED);
            var approved = gate.review("wf-execution", "a");
            assertEquals("COMPLETED", approved.getString("status"));
            assertEquals("APPROVED", approved.getString("decision"));
            assertTrue(gate.review("wf-execution", "a").getBoolean("replayed"));
            var second = worker.runOne("wf-execution");
            assertEquals("b", second.getString("taskId"));
            assertEquals(2, httpCalls.get());
            assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper)
                .inspect("wf-execution").acceptanceStatus());
            assertEquals("COMPLETED", gate.review("wf-execution", "b").getString("status"));
            try (var reopened = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                assertTrue(reopened.list("wf-execution").stream().allMatch(w -> "COMPLETED".equals(w.status())));
            }
            var manifest = new ExecutionAcceptanceManifest(root, mapper).inspect("wf-execution");
            assertEquals("VERIFIED", manifest.acceptanceStatus());
            assertEquals(2, manifest.tasks().size());
            assertTrue(manifest.tasks().stream().allMatch(t -> t.auditId() != null && t.artifactSha256() != null));
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));
            assertEquals(2, httpCalls.get());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            http.stop(0);
        }
    }

    private static void awaitRegistration(io.vertx.core.eventbus.MessageConsumer<JsonObject> consumer) throws Exception {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        consumer.completionHandler(ar -> {
            if (ar.succeeded()) ready.complete(null);
            else ready.completeExceptionally(ar.cause());
        });
        ready.get(5, TimeUnit.SECONDS);
    }
}
