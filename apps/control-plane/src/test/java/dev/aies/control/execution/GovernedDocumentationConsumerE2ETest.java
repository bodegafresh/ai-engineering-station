package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import dev.aies.control.specialists.SpecialistGovernanceService;
import dev.aies.control.specialists.SpecialistRegistryStore;
import io.vertx.core.json.JsonObject;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real governed documentation service, two SQLite stores, activated registry and HTTP mock. */
class GovernedDocumentationConsumerE2ETest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String WF = "wf-execution", TASK = "a", ID = "software-engineer";
    private static final long POLICY_REVISION = 42;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"software-engineer",
        "version":"1.0.0","displayName":"Software Engineer","discipline":"engineering.software",
        "competencies":[{"capabilityId":"software","capabilityVersion":"1.0.0",
        "requiredProficiency":"ADVANCED"}],"roles":["EXECUTOR"],"requiredTools":[],
        "maxConcurrent":1,"costUnits":3}
        """;

    private record Fixture(GovernedSpecialistSelector selector,
                           GovernedSpecialistSelector.AssignmentSnapshot snapshot,
                           String token, String sourceSha) {}

    private Fixture fixture() throws Exception {
        Files.writeString(root.resolve("A.java"), "public class A { public int value() { return 1; } }\n");
        var sourceSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(root.resolve("A.java"))));
        Path registry = root.resolve("registry.sqlite");
        String hash;
        try (var store = new SpecialistRegistryStore(registry, mapper)) {
            hash = store.propose(MANIFEST).manifestSha256();
        }
        var actor = new SpecialistGovernanceService.Principal("human-operator", Set.of(
            "specialists:validate", "specialists:approve", "specialists:activate", "specialists:revoke"));
        try (var governance = new SpecialistGovernanceService(registry, mapper)) {
            governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.VALIDATE, 0, hash, actor);
            governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.APPROVE, 1, hash, actor);
            governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.ACTIVATE, 2, hash, actor);
        }
        var selector = new GovernedSpecialistSelector(registry, mapper, true);
        var snapshot = selector.snapshotForDispatch(ID, "software");
        String token;
        try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
            handoff.authorizeCapability(new ExecutionCapabilityAuthorization(
                WF, TASK, "workspace-documentation", "A.java", sourceSha));
            token = handoff.claim(WF, 3, 600).orElseThrow().leaseToken();
            handoff.bindGovernedAttempt(attempt(token, snapshot));
        }
        return new Fixture(selector, snapshot, token, sourceSha);
    }

    private static ExecutionHandoffStore.GovernedAttempt attempt(String token,
            GovernedSpecialistSelector.AssignmentSnapshot s) {
        return new ExecutionHandoffStore.GovernedAttempt(WF, TASK, token, s.specialistId(),
            s.version(), s.capability(), s.manifestSha256(), s.registryRevision());
    }
    private static GovernedToolResourcePolicy.Grant read() {
        return new GovernedToolResourcePolicy.Grant("workspace-documentation", "read-source", "A.java");
    }
    private static GovernedToolResourcePolicy.Grant invoke() {
        return new GovernedToolResourcePolicy.Grant("workspace-documentation", "invoke-model", "ollama:software-engineer");
    }
    private void approve(Fixture f, List<GovernedToolResourcePolicy.Grant> grants) throws Exception {
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("journal/governed-tool-policies.sqlite"))) {
            policies.approve(new GovernedToolResourcePolicy.Snapshot(WF, TASK, f.token(), ID,
                f.snapshot().version(), POLICY_REVISION, Instant.parse("2099-01-01T00:00:00Z"), false, grants));
        }
        try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
            handoff.bindGovernedPolicyRevision(attempt(f.token(), f.snapshot()), POLICY_REVISION);
        }
    }
    private static JsonObject request(Fixture f) {
        return new JsonObject().put("workflowId", WF).put("taskId", TASK).put("attemptId", f.token())
            .put("sourcePath", "A.java").put("approvedSourceSha256", f.sourceSha()).put("approved", true);
    }

    @Test void missingReadGrantCannotInvokeModelOrPublishDocument() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            assertThrows(SecurityException.class, () -> service.generate(request(f)));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
        }
    }

    @Test void missingInferenceGrantDeniesBeforeExternalHttp() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            assertThrows(SecurityException.class, () -> service.generate(request(f)));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
        }
    }

    @Test void exactGrantsGenerateOnceAndRevocationPreventsReplay() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            JsonObject first = service.generate(request(f));
            assertEquals("EVIDENCE_PENDING", first.getString("status"));
            assertEquals(1, calls.get());
            assertTrue(Files.readString(root.resolve(first.getString("documentPath"))).contains("Documents value"));
            JsonObject replay = service.generate(request(f));
            assertEquals(Boolean.TRUE, replay.getBoolean("replayed"));
            assertEquals(first.getString("artifactRecordId"), replay.getString("artifactRecordId"));
            assertEquals(1, calls.get());
            try (var policies = new GovernedToolResourcePolicyStore(root.resolve("journal/governed-tool-policies.sqlite"))) {
                policies.revoke(WF, TASK, f.token());
            }
            assertThrows(SecurityException.class, () -> service.generate(request(f)));
            assertEquals(1, calls.get());
        }
    }

    /** Concurrent deliveries for the same durable attempt cannot both invoke Ollama. */
    @Test void concurrentDeliveriesReserveOnlyOneModelInvocation() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Boolean> operation = () -> {
                    start.await();
                    try {
                        service.generate(request(f));
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                };
                var first = pool.submit(operation);
                var second = pool.submit(operation);
                start.countDown();
                boolean a = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
                boolean b = second.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(a || b, "At least one delivery must complete");
                assertEquals(1, calls.get(), "Durable reservation prevents duplicate HTTP effects");
                assertTrue(Files.isDirectory(root.resolve(".aies/generated-docs")));
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    /** SQLite availability is part of authorization: a missing policy must not become a grant. */
    @Test void missingPolicyDatabaseFailsClosedWithoutModelInvocation() throws Exception {
        Fixture f = fixture();
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            assertThrows(Exception.class, () -> service.generate(request(f)));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
        }
    }

    /** Real Vert.x local Event Bus delivery through the production consumer; no Quarkus CDI boot. */
    @Test void eventBusRevocationBlocksSubsequentDeliveryWithoutNewHttp() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls); var bus = new DocumentationBusHarness(f, mock.port())) {
            JsonObject first = bus.request(request(f));
            assertEquals("EVIDENCE_PENDING", first.getString("status"));
            assertEquals(1, calls.get());
            try (var policies = new GovernedToolResourcePolicyStore(root.resolve("journal/governed-tool-policies.sqlite"))) {
                policies.revoke(WF, TASK, f.token());
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> bus.request(request(f)));
            assertEquals(1, calls.get(), "Revoked delivery cannot reach model");
        }
    }

    /** An audit INSERT failure must never become an ALLOW or invoke the external model. */
    @Test void eventBusAuditWriteFailureDeniesBeforeHttp() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        try (var db = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + root.resolve("journal/governed-tool-policies.sqlite"));
             var statement = db.createStatement()) {
            statement.execute("CREATE TRIGGER deny_allow_audit BEFORE INSERT ON governed_policy_audit " +
                "WHEN NEW.decision='ALLOW' BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls); var bus = new DocumentationBusHarness(f, mock.port())) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> bus.request(request(f)));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
        }
    }

    /** Failed audit persistence cannot reserve the model; removing the injected fault permits a safe retry. */
    @Test void eventBusAuditFailureIsRecoverableWithoutPrematureExternalEffect() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        Path policyFile = root.resolve("journal/governed-tool-policies.sqlite");
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + policyFile);
             var statement = db.createStatement()) {
            statement.execute("CREATE TRIGGER fail_allow BEFORE INSERT ON governed_policy_audit " +
                "WHEN NEW.decision='ALLOW' BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls); var bus = new DocumentationBusHarness(f, mock.port())) {
            assertThrows(java.util.concurrent.ExecutionException.class, () -> bus.request(request(f)));
            assertEquals(0, calls.get(), "Audit failure must precede any HTTP effect");
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
            try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
                assertTrue(handoff.effect(WF, TASK, f.token()).isEmpty(),
                    "Audit failure must not reserve a model invocation");
            }
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + policyFile);
                 var statement = db.createStatement()) {
                statement.execute("DROP TRIGGER fail_allow");
            }
            JsonObject completed = bus.request(request(f));
            assertEquals("EVIDENCE_PENDING", completed.getString("status"));
            assertEquals(1, calls.get());
            JsonObject replay = bus.request(request(f));
            assertEquals(Boolean.TRUE, replay.getBoolean("replayed"));
            assertEquals(1, calls.get(), "Retry and replay must not duplicate HTTP");
        }
    }

    /** Concurrent Event Bus requests are serialized by durable model invocation reservation. */
    @Test void concurrentEventBusDeliveriesCannotDoubleInvokeModel() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls); var bus = new DocumentationBusHarness(f, mock.port())) {
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                java.util.concurrent.Callable<Boolean> delivery = () -> {
                    start.await();
                    try { bus.request(request(f)); return true; }
                    catch (java.util.concurrent.ExecutionException expectedConflict) { return false; }
                };
                var a = pool.submit(delivery);
                var b = pool.submit(delivery);
                start.countDown();
                boolean first = a.get(20, java.util.concurrent.TimeUnit.SECONDS);
                boolean second = b.get(20, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(first || second, "At least one delivery must complete");
                assertEquals(1, calls.get(), "Only one durable invocation may cross HTTP");
            } finally {
                pool.shutdownNow();
                assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    /** A revoked policy cannot publish a response already in flight to Ollama. */
    @Test void revocationDuringHttpDeniesPublicationAndLeavesAmbiguousReservation() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        var arrived = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/generate", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            arrived.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] body = "{\"response\":\"# A\\nDocuments value().\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var service = service(f, server.getAddress().getPort());
            var pending = pool.submit(() -> service.generate(request(f)));
            assertTrue(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS), "HTTP request must arrive");
            try (var policies = new GovernedToolResourcePolicyStore(root.resolve("journal/governed-tool-policies.sqlite"))) {
                policies.revoke(WF, TASK, f.token());
            }
            release.countDown();
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> pending.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, calls.get(), "An already sent HTTP request cannot be cancelled retroactively");
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")), "Revoked output must not be published");
            try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
                assertTrue(handoff.modelInvocationReserved(WF, TASK, f.token()));
                assertEquals("AMBIGUOUS_INVOCATION", handoff.invocationRecoveryStatus(WF, TASK, f.token()));
            }
            assertThrows(SecurityException.class, () -> service.generate(request(f)));
            assertEquals(1, calls.get(), "Revocation must deny retry before HTTP");
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            server.stop(0);
        }
    }

    /** A durable model reservation is never silently replayed after an ambiguous crash. */
    @Test void ambiguousReservedInvocationCannotBeAutomaticallyRetried() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
            handoff.recordEffect(WF, TASK, f.token(), "PREPARED", null, null, null);
            assertTrue(handoff.reserveModelInvocation(WF, TASK, f.token(), "A.java", f.sourceSha()));
            assertEquals("AMBIGUOUS_INVOCATION", handoff.invocationRecoveryStatus(WF, TASK, f.token()));
        }
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            assertThrows(IllegalStateException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(0, calls.get());
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")));
        }
    }

    /** Production Event Bus component configured explicitly for an isolated Vert.x instance. */
    private final class DocumentationBusHarness implements AutoCloseable {
        private final io.vertx.core.Vertx vertx = io.vertx.core.Vertx.vertx();
        private final WorkspaceDocumentationEventBus consumer = new WorkspaceDocumentationEventBus();
        DocumentationBusHarness(Fixture f, int port) throws Exception {
            set("vertx", vertx);
            set("mapper", mapper);
            set("enabled", true);
            set("workspaceRoot", root.toString());
            set("dataRoot", root.resolve("journal").toString());
            set("baseUrl", "http://127.0.0.1:" + port);
            set("model", "qwen3:4b");
            set("timeoutSeconds", 5);
            set("governedFlag", "true");
            set("governedDatabase", root.resolve("registry.sqlite").toString());
            consumer.start();
        }
        private void set(String field, Object value) throws Exception {
            var f = WorkspaceDocumentationEventBus.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(consumer, value);
        }
        JsonObject request(JsonObject request) throws Exception {
            // The production consumer verifies this snapshot against the activated registry.
            var snapshot = new GovernedSpecialistSelector(
                root.resolve("registry.sqlite"), mapper, true)
                .snapshotForDispatch(ID, "software");
            var body = request.copy().put("governedSpecialistId", snapshot.specialistId())
                .put("governedSpecialistVersion", snapshot.version())
                .put("governedCapability", snapshot.capability())
                .put("governedManifestSha256", snapshot.manifestSha256())
                .put("governedRegistryRevision", snapshot.registryRevision());
            return vertx.eventBus().<JsonObject>request(WorkspaceDocumentationEventBus.GENERATE, body,
                    new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(10000))
                .toCompletionStage().toCompletableFuture().get(15, java.util.concurrent.TimeUnit.SECONDS).body();
        }
        @Override public void close() throws Exception {
            consumer.stop();
            vertx.close().toCompletionStage().toCompletableFuture()
                .get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private WorkspaceDocumentationService service(Fixture f, int port) throws Exception {
        var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper,
            "http://127.0.0.1:" + port, "qwen3:4b", 5);
        return new WorkspaceDocumentationService(root, root.resolve("journal"), executor,
            mapper, f.selector(), f.snapshot());
    }
    private static final class MockModel implements AutoCloseable {
        private final HttpServer server;
        MockModel(AtomicInteger calls) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", exchange -> {
                calls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] response = "{\"response\":\"# A\\nDocuments value().\"}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                try (var out = exchange.getResponseBody()) { out.write(response); }
            });
            server.start();
        }
        int port() { return server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
