package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** F4.2: retry exhaustion, durable restart, malformed specialist replies and DAG isolation. */
class F42ExecutionRecoveryTest {
    @TempDir Path root;

    @Test void failedSpecialistBecomesDeadAndNeverUnlocksDependentTask() throws Exception {
        var mapper = new ObjectMapper();
        var vertx = Vertx.vertx();
        var calls = new AtomicInteger();
        var consumer = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, message -> {
            calls.incrementAndGet();
            var req = message.body();
            // Correlated response, but explicitly NOT executed: must never create an artifact.
            message.reply(new JsonObject()
                .put("workflowId", req.getString("workflowId"))
                .put("taskId", req.getString("taskId"))
                .put("attemptId", req.getString("attemptId"))
                .put("executed", false)
                .put("content", "Not actually executed"));
        });
        try {
            var ready = new java.util.concurrent.CompletableFuture<Void>();
            consumer.completionHandler(ar -> {
                if (ar.succeeded()) ready.complete(null);
                else ready.completeExceptionally(ar.cause());
            });
            ready.get(5, TimeUnit.SECONDS);
            try (var store = handoff(mapper)) {
                store.importPlan(ExecutionGraphTest.plan(
                    ExecutionGraphTest.task("b", "a"), ExecutionGraphTest.task("a")));
            }
            var worker = new ExecutionWorkerEngine(vertx, mapper, root, 2, 60, 10);
            var first = worker.runOne("wf-execution");
            assertEquals("a", first.getString("taskId"));
            assertEquals("RETRY_PENDING", first.getString("status"), first.encodePrettily());
            try (var reopened = handoff(mapper)) {
                assertEquals("PENDING", status(reopened, "a"));
                assertEquals("PENDING", status(reopened, "b"));
            }
            var second = worker.runOne("wf-execution");
            assertEquals("a", second.getString("taskId"));
            assertEquals("DEAD", second.getString("status"), second.encodePrettily());
            assertEquals(2, calls.get());
            try (var reopened = handoff(mapper)) {
                assertEquals("DEAD", status(reopened, "a"));
                assertEquals("PENDING", status(reopened, "b"));
                assertTrue(reopened.claim("wf-execution", 2, 60).isEmpty());
            }
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));
            assertEquals(2, calls.get());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test void wrongCorrelationCannotBeStagedOrMarkedCompleted() throws Exception {
        var mapper = new ObjectMapper();
        var vertx = Vertx.vertx();
        var consumer = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, message -> {
            var req = message.body();
            message.reply(new JsonObject()
                .put("workflowId", req.getString("workflowId"))
                .put("taskId", "different-task")
                .put("attemptId", req.getString("attemptId"))
                .put("executed", true)
                .put("content", "Untrusted result"));
        });
        try {
            var ready = new java.util.concurrent.CompletableFuture<Void>();
            consumer.completionHandler(ar -> {
                if (ar.succeeded()) ready.complete(null);
                else ready.completeExceptionally(ar.cause());
            });
            ready.get(5, TimeUnit.SECONDS);
            try (var store = handoff(mapper)) {
                store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            }
            var result = new ExecutionWorkerEngine(vertx, mapper, root, 1, 60, 10)
                .runOne("wf-execution");
            assertEquals("DEAD", result.getString("status"), result.encodePrettily());
            assertEquals("SecurityException", result.getString("failureType"));
            assertNull(result.getString("artifactRecordId"));
            try (var store = handoff(mapper)) {
                assertEquals("DEAD", status(store, "a"));
                assertTrue(store.approvedAuditId("wf-execution", "a").isEmpty());
            }
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    private ExecutionHandoffStore handoff(ObjectMapper mapper) throws Exception {
        return new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
    }
    private static String status(ExecutionHandoffStore store, String taskId) throws Exception {
        return store.list("wf-execution").stream()
            .filter(w -> w.taskId().equals(taskId))
            .findFirst().orElseThrow().status();
    }
}
