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

    /** A failed durable ALLOW after HTTP must not publish or automatically retry an ambiguous invocation. */
    @Test void postHttpAuditFailureDeniesPublicationAndPreservesAmbiguousReservation() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        Path policyFile = root.resolve("journal/governed-tool-policies.sqlite");
        // ALLOW #1 = read-source; #2 = invoke-model; #3 = post-HTTP reauthorization.
        // Fail only the third authorization, after the external effect has occurred.
        try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + policyFile);
             var statement = db.createStatement()) {
            statement.execute("CREATE TRIGGER fail_third_allow BEFORE INSERT ON governed_policy_audit " +
                "WHEN NEW.decision='ALLOW' AND " +
                "(SELECT COUNT(*) FROM governed_policy_audit WHERE decision='ALLOW') >= 2 " +
                "BEGIN SELECT RAISE(ABORT, 'injected post-HTTP audit failure'); END");
        }
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var service = service(f, mock.port());
            assertThrows(java.sql.SQLException.class, () -> service.generate(request(f)));
            assertEquals(1, calls.get(), "HTTP already occurred before the injected audit failure");
            assertFalse(Files.exists(root.resolve(".aies/generated-docs")),
                "Failed post-HTTP audit must not publish a document");
            try (var handoff = new ExecutionHandoffStore(root.resolve("journal/execution-handoff.sqlite"), mapper)) {
                assertTrue(handoff.modelInvocationReserved(WF, TASK, f.token()));
                assertEquals("AMBIGUOUS_INVOCATION", handoff.invocationRecoveryStatus(WF, TASK, f.token()));
            }
            try (var db = java.sql.DriverManager.getConnection("jdbc:sqlite:" + policyFile);
                 var statement = db.createStatement()) {
                statement.execute("DROP TRIGGER fail_third_allow");
            }
            assertThrows(IllegalStateException.class, () -> service.generate(request(f)),
                "Clearing the fault must not replay a reserved HTTP invocation");
            assertEquals(1, calls.get());
        }
    }

    /** Deterministic exception boundaries model process interruption; restart uses fresh service instances. */
    @Test void restartReconcilesEveryPublicationBoundaryWithoutSecondHttp() throws Exception {
        // JUnit runs each phase in a distinct test method below; this method exercises
        // the journal-to-staging boundary.
        runPublicationBoundaryScenario("AFTER_FILE_JOURNAL");
    }

    @Test void restartAfterAtomicMoveDoesNotRepeatHttp() throws Exception {
        runPublicationBoundaryScenario("AFTER_ATOMIC_MOVE");
    }

    @Test void restartAfterArtifactAppendDoesNotDuplicateEvidence() throws Exception {
        runPublicationBoundaryScenario("AFTER_ARTIFACT_APPEND");
    }

    private void runPublicationBoundaryScenario(String phase) throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var first = service(f, mock.port());
            first.publicationCheckpoint(reached -> {
                if (phase.equals(reached)) throw new java.io.IOException("Injected " + reached);
            });
            assertThrows(java.io.IOException.class, () -> first.generate(request(f)));
            assertEquals(1, calls.get(), "HTTP must have completed before the interruption");
            var restarted = service(f, mock.port());
            JsonObject recovered = restarted.generate(request(f));
            assertEquals("EVIDENCE_PENDING", recovered.getString("status"));
            assertEquals(1, calls.get(), "Restart must never invoke model again");
            assertEquals(Boolean.TRUE, restarted.generate(request(f)).getBoolean("replayed"));
            assertEquals(1, calls.get());
        }
    }

    /** A separate JVM halts without cleanup; the parent JVM must recover durable state. */
    @Test void abruptSubprocessTerminationRecoversPublicationWithoutSecondHttp() throws Exception {
        for (String checkpoint : List.of("AFTER_FILE_JOURNAL", "AFTER_ATOMIC_MOVE", "AFTER_ARTIFACT_APPEND")) {
            // A unique workspace per phase avoids reusing the attempt and its durable reservation.
            Path previousRoot = root;
            root = Files.createDirectory(previousRoot.resolve(checkpoint.toLowerCase(java.util.Locale.ROOT)));
            try {
                Fixture f = fixture();
                approve(f, List.of(read(), invoke()));
                AtomicInteger calls = new AtomicInteger();
                try (var mock = new MockModel(calls)) {
                    var command = new java.util.ArrayList<String>();
                    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
                    command.add("-cp");
                    command.add(subprocessClasspath());
                    command.add(GovernedDocumentationProcessCrashChild.class.getName());
                    command.add(root.toString());
                    command.add(Integer.toString(mock.port()));
                    command.add(f.token());
                    command.add(f.sourceSha());
                    command.add(checkpoint);
                    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                    try {
                        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                            "Subprocess timeout at " + checkpoint);
                        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertEquals(73, process.exitValue(), "Subprocess did not reach " + checkpoint + ": " + output);
                    } finally {
                        if (process.isAlive()) process.destroyForcibly().waitFor();
                    }
                    assertEquals(1, calls.get(), "Exactly one observed HTTP call before JVM halt");
                    var recovered = service(f, mock.port()).generate(request(f));
                    assertEquals("EVIDENCE_PENDING", recovered.getString("status"));
                    assertEquals(1, calls.get(), "Recovery must not re-invoke HTTP");
                    var replayed = service(f, mock.port()).generate(request(f));
                    assertEquals(Boolean.TRUE, replayed.getBoolean("replayed"));
                    assertEquals(recovered.getString("artifactRecordId"), replayed.getString("artifactRecordId"));
                    assertEquals(1, calls.get());
                    assertEquals(recovered.getString("documentSha256"),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                            Files.readAllBytes(root.resolve(recovered.getString("documentPath"))))));
                }
            } finally { root = previousRoot; }
        }
    }

    /** Gradle's test worker may use a classloader rather than java.class.path for project dependencies. */
    private static String subprocessClasspath() throws Exception {
        var entries = new java.util.LinkedHashSet<String>();
        entries.addAll(java.util.Arrays.asList(System.getProperty("java.class.path", "").split(
            java.util.regex.Pattern.quote(java.io.File.pathSeparator))));
        for (ClassLoader loader = GovernedDocumentationConsumerE2ETest.class.getClassLoader();
             loader != null; loader = loader.getParent()) {
            if (loader instanceof java.net.URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if ("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    /** A changed staged file must never be promoted after a crash boundary. */
    @Test void alteredStagingIsRejectedWithoutReinvokingModel() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var first = service(f, mock.port());
            first.publicationCheckpoint(phase -> {
                if ("AFTER_FILE_JOURNAL".equals(phase)) throw new java.io.IOException("Injected crash boundary");
            });
            assertThrows(java.io.IOException.class, () -> first.generate(request(f)));
            assertEquals(1, calls.get());
            Path staged = root.resolve(".aies/generated-docs/" + WF + "-" + TASK + "-" + f.token() + ".md.staging");
            assertTrue(Files.isRegularFile(staged));
            Files.writeString(staged, "tampered content", StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(1, calls.get(), "Tampered staging must not trigger another HTTP request");
            assertFalse(Files.exists(staged.resolveSibling(staged.getFileName().toString().replace(".staging", ""))));
        }
    }

    /** A changed final file must never be accepted during recovery. */
    @Test void alteredFinalMaterialIsRejectedWithoutReinvokingModel() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            var first = service(f, mock.port());
            first.publicationCheckpoint(phase -> {
                if ("AFTER_ATOMIC_MOVE".equals(phase)) throw new java.io.IOException("Injected crash boundary");
            });
            assertThrows(java.io.IOException.class, () -> first.generate(request(f)));
            assertEquals(1, calls.get());
            Path output = root.resolve(".aies/generated-docs/" + WF + "-" + TASK + "-" + f.token() + ".md");
            assertTrue(Files.isRegularFile(output));
            Files.writeString(output, "tampered content", StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(1, calls.get(), "Tampered output must not trigger another HTTP request");
        }
    }

    /** Replay of a completed effect must verify material bytes again. */
    @Test void alteredCompletedArtifactIsRejectedOnReplay() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            JsonObject result = service(f, mock.port()).generate(request(f));
            assertEquals("EVIDENCE_PENDING", result.getString("status"));
            assertEquals(1, calls.get());
            Path output = root.resolve(result.getString("documentPath"));
            Files.writeString(output, "tampered content", StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(1, calls.get(), "Replay must not call HTTP");
        }
    }

    /** 12t: after a real crash, a different JVM must recover without a second model request. */
    @Test void separateJvmRecoversAfterAbruptCrashAtEveryPublicationBoundary() throws Exception {
        for (String checkpoint : List.of("AFTER_FILE_JOURNAL", "AFTER_ATOMIC_MOVE", "AFTER_ARTIFACT_APPEND")) {
            Path previousRoot = root;
            root = Files.createDirectory(previousRoot.resolve("child_recovery_" + checkpoint));
            try {
                Fixture f = fixture();
                approve(f, List.of(read(), invoke()));
                AtomicInteger calls = new AtomicInteger();
                try (var mock = new MockModel(calls)) {
                    var crash = startDocumentationChild(
                        GovernedDocumentationProcessCrashChild.class, f, mock.port(), checkpoint);
                    assertEquals(73, awaitDocumentationChild(crash),
                        "Crash subprocess missed checkpoint " + checkpoint);
                    assertEquals(1, calls.get());
                    var recover = startDocumentationChild(
                        GovernedDocumentationRecoveryChild.class, f, mock.port());
                    assertEquals(0, awaitDocumentationChild(recover),
                        "Recovery subprocess failed " + checkpoint);
                    assertEquals(1, calls.get(), "Recovery in a different JVM must not reinvoke HTTP");
                    var replay = service(f, mock.port()).generate(request(f));
                    assertEquals(Boolean.TRUE, replay.getBoolean("replayed"));
                    assertEquals(1, calls.get());
                }
            } finally { root = previousRoot; }
        }
    }

    /** Two independent JVMs replaying an already completed attempt must not invoke HTTP. */
    @Test void twoJvmConcurrentReplayUsesOneDurableArtifact() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            JsonObject first = service(f, mock.port()).generate(request(f));
            assertEquals(1, calls.get());
            var a = startDocumentationChild(GovernedDocumentationRecoveryChild.class, f, mock.port());
            var b = startDocumentationChild(GovernedDocumentationRecoveryChild.class, f, mock.port());
            assertEquals(0, awaitDocumentationChild(a));
            assertEquals(0, awaitDocumentationChild(b));
            assertEquals(1, calls.get(), "Two JVM replays must not invoke model");
            var again = service(f, mock.port()).generate(request(f));
            assertEquals(first.getString("artifactRecordId"), again.getString("artifactRecordId"));
            assertEquals(Boolean.TRUE, again.getBoolean("replayed"));
        }
    }

    /** H02-D2C2f.18: a different JVM cannot materialize a staged file after durable revocation. */
    @Test void revokedStagingCannotBeRecoveredByIndependentJvm() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            Process crash = startDocumentationChild(
                GovernedDocumentationProcessCrashChild.class, f, mock.port(), "AFTER_FILE_JOURNAL");
            assertEquals(73, awaitDocumentationChild(crash));
            assertEquals(1, calls.get());
            Path output = root.resolve(".aies/generated-docs/" + WF + "-" + TASK + "-" + f.token() + ".md");
            Path staging = output.resolveSibling(output.getFileName() + ".staging");
            assertTrue(Files.isRegularFile(staging));
            assertFalse(Files.exists(output));
            try (var policies = new GovernedToolResourcePolicyStore(root.resolve("journal/governed-tool-policies.sqlite"))) {
                policies.revoke(WF, TASK, f.token());
            }
            Process recovery = startDocumentationChild(GovernedDocumentationRecoveryChild.class, f, mock.port());
            assertNotEquals(0, awaitDeniedRecoveryChild(recovery), "Revoked staging was recovered");
            assertFalse(Files.exists(output), "Revoked staging must not be materialized");
            assertEquals(1, calls.get(), "Recovery cannot reinvoke ambiguous model request");
            assertThrows(SecurityException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(1, calls.get());
        }
    }

    /** H02-D2C2f.18: independent recovery rejects bytes that no longer match the durable journal. */
    @Test void tamperedStagingCannotBeRecoveredByIndependentJvm() throws Exception {
        Fixture f = fixture();
        approve(f, List.of(read(), invoke()));
        AtomicInteger calls = new AtomicInteger();
        try (var mock = new MockModel(calls)) {
            Process crash = startDocumentationChild(
                GovernedDocumentationProcessCrashChild.class, f, mock.port(), "AFTER_FILE_JOURNAL");
            assertEquals(73, awaitDocumentationChild(crash));
            Path output = root.resolve(".aies/generated-docs/" + WF + "-" + TASK + "-" + f.token() + ".md");
            Path staging = output.resolveSibling(output.getFileName() + ".staging");
            assertTrue(Files.isRegularFile(staging));
            Files.writeString(staging, "tampered after process crash", StandardCharsets.UTF_8);
            Process recovery = startDocumentationChild(GovernedDocumentationRecoveryChild.class, f, mock.port());
            assertNotEquals(0, awaitDeniedRecoveryChild(recovery), "Tampered staging was recovered");
            assertFalse(Files.exists(output));
            assertEquals(1, calls.get(), "No second model invocation on tampered staging");
            assertThrows(SecurityException.class, () -> service(f, mock.port()).generate(request(f)));
            assertEquals(1, calls.get());
        }
    }

    private static int awaitDeniedRecoveryChild(Process process) throws Exception {
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                "Recovery child timed out");
            int code = process.exitValue();
            assertNotEquals(0, code, "Recovery child unexpectedly succeeded");
            return code;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private Process startDocumentationChild(Class<?> type, Fixture f, int port, String... extra) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(subprocessClasspath());
        command.add(type.getName());
        command.add(root.toString());
        command.add(Integer.toString(port));
        command.add(f.token());
        command.add(f.sourceSha());
        command.addAll(java.util.Arrays.asList(extra));
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static int awaitDocumentationChild(Process process) throws Exception {
        try {
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
                "Documentation child process timeout");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.exitValue();
            assertTrue(code == 0 || code == 73,
                "Unexpected child exit " + code + ": " + output);
            return code;
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    /** 12u: two independent JVMs contend for recovery of the same unfinished effect. */
    @Test void twoJvmCompeteToRecoverUnfinishedPublication() throws Exception {
        for (String checkpoint : List.of("AFTER_FILE_JOURNAL", "AFTER_ATOMIC_MOVE",
                "AFTER_ARTIFACT_APPEND")) {
            Path previousRoot = root;
            root = Files.createDirectory(previousRoot.resolve("race_" + checkpoint));
            try {
                Fixture f = fixture();
                approve(f, List.of(read(), invoke()));
                AtomicInteger calls = new AtomicInteger();
                try (var mock = new MockModel(calls)) {
                    Process crash = startDocumentationChild(
                        GovernedDocumentationProcessCrashChild.class, f, mock.port(), checkpoint);
                    assertEquals(73, awaitDocumentationChild(crash));
                    assertEquals(1, calls.get());
                    Path gate = root.resolve("recovery-start.gate");
                    Process a = startDocumentationChild(
                        GovernedDocumentationRecoveryRaceChild.class, f, mock.port(), gate.toString());
                    Process b = startDocumentationChild(
                        GovernedDocumentationRecoveryRaceChild.class, f, mock.port(), gate.toString());
                    try {
                        Files.writeString(gate, "start", StandardCharsets.UTF_8);
                        int first = awaitRecoveryContender(a);
                        int second = awaitRecoveryContender(b);
                        assertTrue(first == 0 || second == 0,
                            "At least one contender must recover unfinished material");
                    } finally {
                        if (a.isAlive()) a.destroyForcibly().waitFor();
                        if (b.isAlive()) b.destroyForcibly().waitFor();
                    }
                    assertEquals(1, calls.get(), "Recovery must not reinvoke model");
                    JsonObject replay = service(f, mock.port()).generate(request(f));
                    assertEquals(Boolean.TRUE, replay.getBoolean("replayed"));
                    assertEquals(1, calls.get());
                    assertEquals(replay.getString("documentSha256"),
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                            Files.readAllBytes(root.resolve(replay.getString("documentPath"))))));
                }
            } finally { root = previousRoot; }
        }
    }

    private static int awaitRecoveryContender(Process process) throws Exception {
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
            "Recovery contender timeout");
        int code = process.exitValue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(code == 0 || code == 1, "Unexpected recovery exit " + code + ": " + output);
        return code;
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
