package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** G10-I: deterministic failure injection against isolated durable SQLite and real material bytes. */
class F43G10IFailureInjectionTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String WF = "wf-execution";
    private static final String FIRST = "a";
    private Path database() { return root.resolve("execution-handoff.sqlite"); }

    private void importDag() throws Exception {
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            assertTrue(store.importPlan(ExecutionGraphTest.plan(
                ExecutionGraphTest.task("b", FIRST), ExecutionGraphTest.task(FIRST))));
        }
    }

    /** Test-only database clock injection; never touches an operator/production database. */
    private void expireLease() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = db.prepareStatement("UPDATE execution_tasks SET lease_until=? WHERE workflow_id=? AND task_id=? AND status='LEASED'")) {
            statement.setString(1, Instant.EPOCH.toString());
            statement.setString(2, WF);
            statement.setString(3, FIRST);
            assertEquals(1, statement.executeUpdate());
        }
    }

    @Test void expiredLeaseIsFencedAndNewAttemptSurvivesReopening() throws Exception {
        importDag();
        String stale;
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            stale = store.claim(WF, 3, 60).orElseThrow().leaseToken();
        }
        expireLease();
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            assertFalse(store.stageEvidence(WF, FIRST, stale, "late-artifact"));
            assertFalse(store.fail(WF, FIRST, stale, true, 3));
            var current = store.claim(WF, 3, 60).orElseThrow();
            assertNotEquals(stale, current.leaseToken());
            assertEquals(2, current.attempts());
            assertFalse(store.stageEvidence(WF, FIRST, stale, "late-artifact"));
            assertFalse(store.fail(WF, FIRST, stale, false, 3));
            assertTrue(store.claim(WF, 3, 60).isEmpty());
        }
        try (var reopened = new ExecutionHandoffStore(database(), mapper)) {
            var first = reopened.list(WF).stream().filter(w -> FIRST.equals(w.taskId())).findFirst().orElseThrow();
            assertEquals("LEASED", first.status());
            assertEquals(2, first.attempts());
            assertNotEquals(stale, first.leaseToken());
            assertEquals("PENDING", reopened.list(WF).stream().filter(w -> "b".equals(w.taskId())).findFirst().orElseThrow().status());
            assertTrue(reopened.approvedAuditId(WF, FIRST).isEmpty());
        }
        assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper).inspect(WF).acceptanceStatus());
    }

    @Test void exhaustedExpiredLeaseCannotBeReclaimedOrUnlockDependencies() throws Exception {
        importDag();
        String stale;
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            stale = store.claim(WF, 1, 60).orElseThrow().leaseToken();
        }
        expireLease();
        try (var store = new ExecutionHandoffStore(database(), mapper)) {
            assertTrue(store.claim(WF, 1, 60).isEmpty());
            assertFalse(store.stageEvidence(WF, FIRST, stale, "late-artifact"));
            assertFalse(store.fail(WF, FIRST, stale, true, 1));
            assertEquals(1, store.list(WF).stream().filter(w -> FIRST.equals(w.taskId())).findFirst().orElseThrow().attempts());
            assertTrue(store.claim(WF, 1, 60).isEmpty());
        }
        assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper).inspect(WF).acceptanceStatus());
    }

    @Test void rejectedAndConflictingAuditReplayCannotUnlockDag() throws Exception {
        importDag();
        try (var handoff = new ExecutionHandoffStore(database(), mapper);
             var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
            var lease = handoff.claim(WF, 2, 60).orElseThrow();
            var artifact = comm.append("ARTIFACT", WF, FIRST, new CommunicationContracts.Artifact(
                "1.0", WF, FIRST, "software-engineer", "execution-result", "text/plain", "output", List.of()));
            String ref = "artifact://communication/" + artifact.id();
            assertTrue(handoff.stageEvidence(WF, FIRST, lease.leaseToken(), artifact.id()));
            var blocked = comm.append("AUDIT", WF, FIRST, new CommunicationContracts.Audit(
                "1.0", WF, artifact.id(), "quality-auditor", CommunicationContracts.AuditDecision.BLOCKED,
                1, 3, List.of("Rejected evidence"), List.of()));
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(WF, FIRST, ref, blocked.id(), comm, null));
            assertTrue(handoff.claim(WF, 2, 60).isEmpty());
            var approved = comm.append("AUDIT", WF, FIRST, new CommunicationContracts.Audit(
                "1.0", WF, artifact.id(), "quality-auditor", CommunicationContracts.AuditDecision.APPROVED,
                1, 3, List.of(), List.of()));
            assertTrue(handoff.approveVerifiedEvidence(WF, FIRST, ref, approved.id(), comm, null));
            assertFalse(handoff.approveVerifiedEvidence(WF, FIRST, ref, approved.id(), comm, null));
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(WF, FIRST, ref, blocked.id(), comm, null));
            assertEquals(approved.id(), handoff.approvedAuditId(WF, FIRST).orElseThrow());
            assertEquals("b", handoff.claim(WF, 2, 60).orElseThrow().taskId());
        }
    }

    @Test void materialTamperBeforeApprovalFailsClosedAndDoesNotUnlockDag() throws Exception {
        importDag();
        Path document = root.resolve(".aies/generated-docs/result.md");
        Files.createDirectories(document.getParent());
        byte[] original = "actual verified bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(document, original);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        try (var handoff = new ExecutionHandoffStore(database(), mapper);
             var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
            var lease = handoff.claim(WF, 2, 60).orElseThrow();
            var artifact = comm.append("ARTIFACT", WF, FIRST, new CommunicationContracts.Artifact(
                "1.0", WF, FIRST, "software-engineer", "execution-result", "text/markdown", "actual verified bytes",
                List.of(WorkspaceMaterialEvidence.reference(".aies/generated-docs/result.md", sha))));
            assertTrue(handoff.stageEvidence(WF, FIRST, lease.leaseToken(), artifact.id()));
            var audit = comm.append("AUDIT", WF, FIRST, new CommunicationContracts.Audit(
                "1.0", WF, artifact.id(), "quality-auditor", CommunicationContracts.AuditDecision.APPROVED,
                1, 3, List.of(), List.of()));
            Files.writeString(document, "altered bytes", StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(
                WF, FIRST, "artifact://communication/" + artifact.id(), audit.id(), comm, root));
            assertEquals("EVIDENCE_PENDING", handoff.list(WF).stream().filter(w -> FIRST.equals(w.taskId())).findFirst().orElseThrow().status());
            assertTrue(handoff.approvedAuditId(WF, FIRST).isEmpty());
            assertTrue(handoff.claim(WF, 2, 60).isEmpty());
        }
        assertEquals("INCOMPLETE", new ExecutionAcceptanceManifest(root, mapper).inspect(WF).acceptanceStatus());
    }
}
