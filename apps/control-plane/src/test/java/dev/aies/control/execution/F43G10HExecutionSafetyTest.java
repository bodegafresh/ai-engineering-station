package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** G10-H: real SQLite, isolated roots, no model, no production writes. */
class F43G10HExecutionSafetyTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private Path database() { return root.resolve("execution-handoff.sqlite"); }
    private void importDag() throws Exception {
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            assertTrue(store.importPlan(ExecutionGraphTest.plan(
                ExecutionGraphTest.task("dependent", "first"), ExecutionGraphTest.task("first"))));
        }
    }

    @Test void staleAndConflictingLeaseTokensCannotStageOrFail() throws Exception {
        importDag();
        String token;
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            var first = store.claim("wf-execution", 2, 60).orElseThrow();
            assertEquals("first", first.taskId());
            token = first.leaseToken();
            assertFalse(store.stageEvidence("wf-execution", "first", "incorrect-token", "artifact-1"));
            assertFalse(store.fail("wf-execution", "first", "incorrect-token", true, 2));
            assertTrue(store.claim("wf-execution", 2, 60).isEmpty());
            assertTrue(store.fail("wf-execution", "first", token, true, 2));
            assertFalse(store.stageEvidence("wf-execution", "first", token, "artifact-1"));
        }
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            var second = store.claim("wf-execution", 2, 60).orElseThrow();
            assertEquals("first", second.taskId());
            assertNotEquals(token, second.leaseToken());
            assertFalse(store.stageEvidence("wf-execution", "first", token, "artifact-1"));
            assertFalse(store.fail("wf-execution", "first", token, true, 2));
            assertTrue(store.stageEvidence("wf-execution", "first", second.leaseToken(), "artifact-2"));
            assertFalse(store.stageEvidence("wf-execution", "first", second.leaseToken(), "artifact-3"));
            assertTrue(store.claim("wf-execution", 2, 60).isEmpty(), "Pending audit must block dependency");
            assertEquals("EVIDENCE_PENDING", store.list("wf-execution").stream()
                .filter(w -> w.taskId().equals("first")).findFirst().orElseThrow().status());
            assertEquals("PENDING", store.list("wf-execution").stream()
                .filter(w -> w.taskId().equals("dependent")).findFirst().orElseThrow().status());
        }
    }

    @Test void concurrentConnectionsClaimAtMostOneTask() throws Exception {
        importDag();
        int contenders = 8;
        var ready = new CountDownLatch(contenders);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(contenders);
        try {
            var results = new ArrayList<java.util.concurrent.Future<ExecutionHandoffStore.Work>>();
            for (int i = 0; i < contenders; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(go.await(10, TimeUnit.SECONDS));
                    try (var store = new ExecutionHandoffStore(database(), mapper)) {
                        return store.claim("wf-execution", 3, 60).orElse(null);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            var claimed = new ArrayList<ExecutionHandoffStore.Work>();
            for (var future : results) {
                var work = future.get(30, TimeUnit.SECONDS);
                if (work != null) claimed.add(work);
            }
            assertEquals(1, claimed.size(), "Exactly one active claim across SQLite connections");
            assertEquals("first", claimed.get(0).taskId());
            assertEquals(1, new HashSet<>(claimed.stream().map(ExecutionHandoffStore.Work::leaseToken).toList()).size());
            try (var reopened = new ExecutionHandoffStore(database(), mapper)) {
                assertEquals(1, reopened.list("wf-execution").stream()
                    .filter(w -> "LEASED".equals(w.status())).count());
                assertEquals(1, reopened.list("wf-execution").stream()
                    .filter(w -> "first".equals(w.taskId())).findFirst().orElseThrow().attempts());
            }
        } finally {
            go.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void terminalFailureAndRestartCannotUnlockDag() throws Exception {
        importDag();
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            var work = store.claim("wf-execution", 1, 60).orElseThrow();
            assertTrue(store.fail("wf-execution", "first", work.leaseToken(), true, 1));
            assertFalse(store.fail("wf-execution", "first", work.leaseToken(), true, 1));
            assertFalse(store.stageEvidence("wf-execution", "first", work.leaseToken(), "artifact-1"));
        }
        try (var reopened = new ExecutionHandoffStore(database(), mapper)) {
            assertTrue(reopened.claim("wf-execution", 1, 60).isEmpty());
            assertEquals("DEAD", reopened.list("wf-execution").stream()
                .filter(w -> w.taskId().equals("first")).findFirst().orElseThrow().status());
            assertEquals("PENDING", reopened.list("wf-execution").stream()
                .filter(w -> w.taskId().equals("dependent")).findFirst().orElseThrow().status());
            assertTrue(reopened.approvedAuditId("wf-execution", "first").isEmpty());
        }
        assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper)
            .inspect("wf-execution").acceptanceStatus());
    }
}
