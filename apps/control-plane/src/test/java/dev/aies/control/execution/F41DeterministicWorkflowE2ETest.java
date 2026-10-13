package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F4.1: deterministic vertical integration, real local Vert.x Event Bus and
 * durable SQLite. No network, shell, Python or LLM calls.
 * Does not claim HTTP intake, Ollama or material filesystem E2E coverage.
 */
class F41DeterministicWorkflowE2ETest {
    @TempDir Path root;

    @Test
    void twoTaskDagRequiresExactAuditAndSurvivesStoreReopen() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Vertx vertx = Vertx.vertx();
        AtomicInteger calls = new AtomicInteger();
        var consumer = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, message -> {
            JsonObject request = message.body();
            calls.incrementAndGet();
            message.reply(new JsonObject()
                .put("workflowId", request.getString("workflowId"))
                .put("taskId", request.getString("taskId"))
                .put("attemptId", request.getString("attemptId"))
                .put("executed", true)
                .put("content", "Deterministic evidence for " + request.getString("taskId")));
        });
        try {
            try (var store = handoff(mapper)) {
                assertTrue(store.importPlan(ExecutionGraphTest.plan(
                    ExecutionGraphTest.task("b", "a"), ExecutionGraphTest.task("a"))));
            }
            ExecutionWorkerEngine worker = new ExecutionWorkerEngine(vertx, mapper, root, 3, 60, 10);
            JsonObject first = worker.runOne("wf-execution");
            assertEquals("a", first.getString("taskId"));
            assertEquals(
                "EVIDENCE_PENDING_VERIFICATION",
                first.getString("status"),
                () -> "Respuesta del worker: " + first.encodePrettily()
                    + ", llamadas al consumidor: " + calls.get()
            );
            assertEquals(1, calls.get());
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));

            try (var store = handoff(mapper); var comm = communications(mapper)) {
                String artifactId = first.getString("artifactRecordId");
                String ref = "artifact://communication/" + artifactId;
                var rejected = comm.append("AUDIT", "wf-execution", "a",
                    audit(artifactId, CommunicationContracts.AuditDecision.BLOCKED));
                assertThrows(SecurityException.class, () ->
                    store.approveVerifiedEvidence("wf-execution", "a", ref, rejected.id(), comm, null));
                assertEquals("EVIDENCE_PENDING", store.list("wf-execution").stream()
                    .filter(w -> w.taskId().equals("a")).findFirst().orElseThrow().status());
                var approved = comm.append("AUDIT", "wf-execution", "a",
                    audit(artifactId, CommunicationContracts.AuditDecision.APPROVED));
                assertTrue(store.approveVerifiedEvidence("wf-execution", "a", ref, approved.id(), comm, null));
                assertFalse(store.approveVerifiedEvidence("wf-execution", "a", ref, approved.id(), comm, null));
            }
            // Reopen SQLite handles to verify durable DAG transition.
            try (var store = handoff(mapper)) {
                assertEquals("COMPLETED", store.list("wf-execution").stream()
                    .filter(w -> w.taskId().equals("a")).findFirst().orElseThrow().status());
            }
            JsonObject second = worker.runOne("wf-execution");
            assertEquals("b", second.getString("taskId"));
            assertEquals(2, calls.get());
            try (var store = handoff(mapper); var comm = communications(mapper)) {
                String id = second.getString("artifactRecordId");
                var approved = comm.append("AUDIT", "wf-execution", "b",
                    audit(id, CommunicationContracts.AuditDecision.APPROVED));
                assertTrue(store.approveVerifiedEvidence("wf-execution", "b",
                    "artifact://communication/" + id, approved.id(), comm, null));
                assertTrue(store.list("wf-execution").stream().allMatch(w -> "COMPLETED".equals(w.status())));
            }
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));
            assertEquals(2, calls.get());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private ExecutionHandoffStore handoff(ObjectMapper mapper) throws Exception {
        return new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
    }
    private CommunicationStore communications(ObjectMapper mapper) throws Exception {
        return new CommunicationStore(root.resolve("communication.sqlite"), mapper);
    }
    private static CommunicationContracts.Audit audit(String subject,
            CommunicationContracts.AuditDecision decision) {
        return CommunicationContracts.validate(new CommunicationContracts.Audit(
            CommunicationContracts.VERSION, "wf-execution", subject, "quality-auditor",
            decision, 1, 3,
            decision == CommunicationContracts.AuditDecision.APPROVED
                ? List.of() : List.of("Evidence rejected"),
            List.of()));
    }
}
