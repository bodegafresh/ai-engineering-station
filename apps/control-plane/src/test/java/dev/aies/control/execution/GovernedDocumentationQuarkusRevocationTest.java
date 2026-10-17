package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import dev.aies.control.specialists.SpecialistGovernanceService;
import dev.aies.control.specialists.SpecialistRegistryStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated negative governed flow through the real CDI Event Bus consumer. */
@QuarkusTest
@TestProfile(GovernedDocumentationQuarkusRevocationTest.Profile.class)
class GovernedDocumentationQuarkusRevocationTest {
    @Inject Vertx vertx;
    @Inject WorkspaceDocumentationEventBus consumer;

    static final class Fixture {
        static final ObjectMapper MAPPER = new ObjectMapper();
        static final String WF = "wf-execution", TASK = "a", ID = "software-engineer";
        static final AtomicInteger HTTP_CALLS = new AtomicInteger();
        static Path root;
        static Path journal;
        static String token;
        static String sha;
        static GovernedSpecialistSelector.AssignmentSnapshot snapshot;
        static HttpServer server;
        static synchronized Map<String,String> initialize() {
            if (root != null) throw new IllegalStateException("Quarkus profile initialized twice");
            try {
                root = Files.createTempDirectory("aies-12n-cdi-");
                journal = Files.createDirectories(root.resolve("journal"));
                Files.writeString(root.resolve("A.java"), "public class A { public int value() { return 1; } }\n");
                sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve("A.java"))));
                String manifest = """
                    {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"software-engineer",
                    "version":"1.0.0","displayName":"Software Engineer","discipline":"engineering.software",
                    "competencies":[{"capabilityId":"software","capabilityVersion":"1.0.0",
                    "requiredProficiency":"ADVANCED"}],"roles":["EXECUTOR"],"requiredTools":[],
                    "maxConcurrent":1,"costUnits":3}
                    """;
                Path registry = root.resolve("registry.sqlite");
                String hash;
                try (var store = new SpecialistRegistryStore(registry, MAPPER)) {
                    hash = store.propose(manifest).manifestSha256();
                }
                var actor = new SpecialistGovernanceService.Principal("human-operator", Set.of(
                    "specialists:validate", "specialists:approve", "specialists:activate", "specialists:revoke"));
                try (var governance = new SpecialistGovernanceService(registry, MAPPER)) {
                    governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.VALIDATE, 0, hash, actor);
                    governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.APPROVE, 1, hash, actor);
                    governance.transition(ID, "1.0.0", SpecialistGovernanceService.Action.ACTIVATE, 2, hash, actor);
                }
                snapshot = new GovernedSpecialistSelector(registry, MAPPER, true)
                    .snapshotForDispatch(ID, "software");
                try (var handoff = new ExecutionHandoffStore(journal.resolve("execution-handoff.sqlite"), MAPPER)) {
                    handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
                    handoff.authorizeCapability(new ExecutionCapabilityAuthorization(
                        WF, TASK, "workspace-documentation", "A.java", sha));
                    token = handoff.claim(WF, 3, 600).orElseThrow().leaseToken();
                    handoff.bindGovernedAttempt(attempt());
                }
                try (var policies = new GovernedToolResourcePolicyStore(journal.resolve("governed-tool-policies.sqlite"))) {
                    policies.approve(new GovernedToolResourcePolicy.Snapshot(WF, TASK, token, ID,
                        snapshot.version(), 42, Instant.parse("2099-01-01T00:00:00Z"), false,
                        List.of(new GovernedToolResourcePolicy.Grant("workspace-documentation", "read-source", "A.java"),
                            new GovernedToolResourcePolicy.Grant("workspace-documentation", "invoke-model", "ollama:software-engineer"))));
                }
                try (var handoff = new ExecutionHandoffStore(journal.resolve("execution-handoff.sqlite"), MAPPER)) {
                    handoff.bindGovernedPolicyRevision(attempt(), 42);
                }
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/api/generate", exchange -> {
                    HTTP_CALLS.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] bytes = "{\"response\":\"# A\\nDocuments value().\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var output = exchange.getResponseBody()) { output.write(bytes); }
                    finally { exchange.close(); }
                });
                server.start();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
                return Map.of(
                    "aies.execution.documentation.enabled", "true",
                    "aies.specialists.governed-selection.enabled", "true",
                    "aies.specialists.governed-selection.database", registry.toString(),
                    "aies.execution.documentation.workspace-root", root.toString(),
                    "aies.agent-communications.data-root", journal.toString(),
                    "aies.model.base-url", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "aies.execution.documentation.timeout-seconds", "5");
            } catch (Exception failure) { throw new IllegalStateException("Unable to initialize governed CDI fixture", failure); }
        }
        static ExecutionHandoffStore.GovernedAttempt attempt() {
            return new ExecutionHandoffStore.GovernedAttempt(WF, TASK, token, snapshot.specialistId(),
                snapshot.version(), snapshot.capability(), snapshot.manifestSha256(), snapshot.registryRevision());
        }
        static JsonObject request() {
            return new JsonObject().put("workflowId", WF).put("taskId", TASK).put("attemptId", token)
                .put("sourcePath", "A.java").put("approvedSourceSha256", sha).put("approved", true)
                .put("governedSpecialistId", snapshot.specialistId())
                .put("governedSpecialistVersion", snapshot.version())
                .put("governedCapability", snapshot.capability())
                .put("governedManifestSha256", snapshot.manifestSha256())
                .put("governedRegistryRevision", snapshot.registryRevision());
        }
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() { return Fixture.initialize(); }
    }

    /** Isolated Quarkus profile: tampering and revocation are distinct fail-closed checks. */
    @Test void cdiRejectsTamperedSourceThenRevokedPolicyWithoutHttp() throws Exception {
        assertNotNull(consumer);
        Path source = Fixture.root.resolve("A.java");
        String original = Files.readString(source);
        try {
            Files.writeString(source, "public class A { public int value() { return 999; } }\n");
            assertThrows(java.util.concurrent.ExecutionException.class,
                () -> vertx.eventBus().<JsonObject>request(WorkspaceDocumentationEventBus.GENERATE,
                    Fixture.request(), new DeliveryOptions().setSendTimeout(5000))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(0, Fixture.HTTP_CALLS.get(), "Tampered source must not invoke model");
        } finally {
            Files.writeString(source, original);
        }
        assertEquals(Fixture.sha, HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))));
        try (var policies = new GovernedToolResourcePolicyStore(
                Fixture.journal.resolve("governed-tool-policies.sqlite"))) {
            policies.revoke(Fixture.WF, Fixture.TASK, Fixture.token);
        }
        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> vertx.eventBus().<JsonObject>request(WorkspaceDocumentationEventBus.GENERATE,
                Fixture.request(), new DeliveryOptions().setSendTimeout(5000))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        assertEquals(0, Fixture.HTTP_CALLS.get(), "Revoked policy must not invoke model");
        try (var policies = new GovernedToolResourcePolicyStore(
                Fixture.journal.resolve("governed-tool-policies.sqlite"))) {
            assertFalse(policies.publicationAdmitted(Fixture.WF, Fixture.TASK, Fixture.token));
        }
    }
}
