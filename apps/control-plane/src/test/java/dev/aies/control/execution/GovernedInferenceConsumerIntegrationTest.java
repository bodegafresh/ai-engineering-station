package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** H02-D2C2f.12c: real inference consumer boundary backed by both SQLite stores. */
class GovernedInferenceConsumerIntegrationTest {
    @TempDir Path root;
    private static final String WF = "wf-execution", TASK = "a", ID = "software-engineer";
    private static final long REVISION = 42;
    private final ObjectMapper mapper = new ObjectMapper();

    private static ExecutionHandoffStore.GovernedAttempt attempt(String token) {
        return new ExecutionHandoffStore.GovernedAttempt(WF, TASK, token, ID, "1.0", "software", "a".repeat(64), 7);
    }
    private static JsonObject body(String token) {
        return new JsonObject().put("workflowId", WF).put("taskId", TASK).put("attemptId", token)
            .put("specialistId", ID).put("governedSpecialistVersion", "1.0")
            .put("capability", "software").put("governedManifestSha256", "a".repeat(64))
            .put("governedRegistryRevision", 7L);
    }
    private static GovernedToolResourcePolicy.Snapshot snapshot(String token, List<GovernedToolResourcePolicy.Grant> grants) {
        return new GovernedToolResourcePolicy.Snapshot(WF, TASK, token, ID, "1.0", REVISION,
            Instant.parse("2099-01-01T00:00:00Z"), false, grants);
    }
    private String lease() throws Exception {
        try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
            String token = handoff.claim(WF, 3, 600).orElseThrow().leaseToken();
            handoff.bindGovernedAttempt(attempt(token));
            return token;
        }
    }
    private void approve(String token, List<GovernedToolResourcePolicy.Grant> grants, boolean bind) throws Exception {
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("governed-tool-policies.sqlite"))) {
            policies.approve(snapshot(token, grants));
        }
        if (bind) {
            try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
                handoff.bindGovernedPolicyRevision(attempt(token), REVISION);
            }
        }
    }
    private void require(JsonObject body) throws Exception {
        GovernedInferenceBoundary.require(body, root, mapper);
    }
    @Test void noPolicyAndUnboundPolicyDenyUntilOperatorRevisionIsBound() throws Exception {
        String token = lease();
        var request = body(token);
        assertThrows(SecurityException.class, () -> require(request));
        approve(token, List.of(new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:" + ID)), false);
        assertThrows(SecurityException.class, () -> require(request));
        try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
            handoff.bindGovernedPolicyRevision(attempt(token), REVISION);
        }
        assertDoesNotThrow(() -> require(request));
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("governed-tool-policies.sqlite"))) {
            var audit = policies.auditTrail(WF, TASK, token);
            assertTrue(audit.stream().anyMatch(e -> "HANDOFF_REJECTED".equals(e.reason())));
            assertTrue(audit.stream().anyMatch(e -> "ALLOW".equals(e.decision())));
        }
    }
    @Test void forgedEventBusGrantCannotExpandServerDerivedResource() throws Exception {
        String token = lease();
        approve(token, List.of(new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:another-specialist")), true);
        var request = body(token).put("tool", "ollama").put("action", "invoke-model")
            .put("resource", "ollama:another-specialist").put("approved", true);
        assertThrows(SecurityException.class, () -> require(request));
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("governed-tool-policies.sqlite"))) {
            assertTrue(policies.auditTrail(WF, TASK, token).stream().anyMatch(e -> "DENY".equals(e.decision())));
        }
    }
    @Test void revokedPolicyRemainsDeniedAcrossIndependentReopenedConnections() throws Exception {
        String token = lease();
        approve(token, List.of(new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:" + ID)), true);
        assertDoesNotThrow(() -> require(body(token)));
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("governed-tool-policies.sqlite"))) {
            policies.revoke(WF, TASK, token);
        }
        assertThrows(SecurityException.class, () -> require(body(token)));
        try (var policies = new GovernedToolResourcePolicyStore(root.resolve("governed-tool-policies.sqlite"))) {
            assertThrows(SecurityException.class, () -> policies.approve(snapshot(token,
                List.of(new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:" + ID)))));
            assertTrue(policies.auditTrail(WF, TASK, token).stream().anyMatch(e -> "REVOKED".equals(e.decision())));
            assertTrue(policies.auditTrail(WF, TASK, token).stream().anyMatch(e -> "DENY".equals(e.decision())));
        }
    }
    @Test void mismatchedFingerprintAndAttemptNeverPassConsumerBoundary() throws Exception {
        String token = lease();
        approve(token, List.of(new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:" + ID)), true);
        assertThrows(SecurityException.class, () -> require(body(token).put("governedManifestSha256", "b".repeat(64))));
        assertThrows(SecurityException.class, () -> require(body("forged-attempt")));
        assertDoesNotThrow(() -> require(body(token)));
    }
}
