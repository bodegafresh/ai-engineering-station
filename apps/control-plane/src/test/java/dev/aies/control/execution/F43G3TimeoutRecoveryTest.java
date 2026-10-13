package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** F4.3-G3: a timed-out Event Bus request must not complete a dependent DAG task. */
class F43G3TimeoutRecoveryTest {
    @TempDir Path root;

    @Test void timeoutAndLateResponseCannotCompleteDag() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Vertx vertx = Vertx.vertx();
        AtomicInteger calls = new AtomicInteger();
        try {
            var consumer = vertx.eventBus().<JsonObject>localConsumer(ExecutionWorkerEngine.EXECUTE, message -> {
                calls.incrementAndGet();
                // The specialist replies after the worker request has timed out.
                vertx.setTimer(3000, ignored -> message.reply(new JsonObject()
                    .put("workflowId", message.body().getString("workflowId"))
                    .put("taskId", message.body().getString("taskId"))
                    .put("attemptId", message.body().getString("attemptId"))
                    .put("executed", true).put("content", "Late model output")));
            });
            CompletableFuture<Void> ready = new CompletableFuture<>();
            consumer.completionHandler(ar -> { if (ar.succeeded()) ready.complete(null); else ready.completeExceptionally(ar.cause()); });
            ready.get(5, TimeUnit.SECONDS);
            try (var store = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                assertTrue(store.importPlan(ExecutionGraphTest.plan(
                    ExecutionGraphTest.task("b", "a"), ExecutionGraphTest.task("a"))));
            }
            var worker = new ExecutionWorkerEngine(vertx, mapper, root, 1, 30, 1);
            var result = worker.runOne("wf-execution");
            assertEquals("a", result.getString("taskId"));
            assertEquals("DEAD", result.getString("status"), result.encodePrettily());
            Thread.sleep(3300); // test thread only; never the Vert.x event loop
            try (var reopened = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                assertEquals("DEAD", reopened.list("wf-execution").stream()
                    .filter(w -> "a".equals(w.taskId())).findFirst().orElseThrow().status());
                assertTrue(reopened.list("wf-execution").stream()
                    .noneMatch(w -> "COMPLETED".equals(w.status())));
            }
            assertFalse(worker.runOne("wf-execution").getBoolean("claimed"));
            assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper)
                .inspect("wf-execution").acceptanceStatus());
            assertEquals(1, calls.get());
        } finally {
            vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }
}
