package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionVerifiedCompletionTest {
    @TempDir Path dir;
    private static final String WF = "wf-execution";
    private static final String TASK = "a";

    @Test void verifiesExactAuditAndUnlocksDependencyOnlyOnce() throws Exception {
        var mapper = new ObjectMapper();
        try (var handoff = new ExecutionHandoffStore(dir.resolve("execution.sqlite"), mapper);
             var communication = new CommunicationStore(dir.resolve("communication.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("b", "a"), ExecutionGraphTest.task("a")));
            var leased = handoff.claim(WF, 3, 60).orElseThrow();
            var artifact = CommunicationContracts.validate(new CommunicationContracts.Artifact(
                "1.0", WF, TASK, "software-engineer", "execution-result", "text/markdown", "Actual tested output", List.of()));
            var evidence = communication.append("ARTIFACT", WF, TASK, artifact);
            var ref = "artifact://communication/" + evidence.id();
            assertTrue(handoff.stageEvidence(WF, TASK, leased.leaseToken(), evidence.id()));
            assertTrue(handoff.claim(WF, 3, 60).isEmpty());
            var wrongSubject = communication.append("AUDIT", WF, TASK, audit("wrong-subject", CommunicationContracts.AuditDecision.APPROVED));
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(WF, TASK, ref, wrongSubject.id(), communication, null));
            var rejected = communication.append("AUDIT", WF, TASK, audit(evidence.id(), CommunicationContracts.AuditDecision.BLOCKED));
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(WF, TASK, ref, rejected.id(), communication, null));
            var foreign = communication.append("AUDIT", WF, "b", audit(evidence.id(), CommunicationContracts.AuditDecision.APPROVED));
            assertThrows(SecurityException.class, () -> handoff.approveVerifiedEvidence(WF, TASK, ref, foreign.id(), communication, null));
            assertThrows(UnsupportedOperationException.class, () -> handoff.approveEvidence(WF, TASK, ref, "fake-audit"));
            assertTrue(handoff.claim(WF, 3, 60).isEmpty());
            var approved = communication.append("AUDIT", WF, TASK, audit(evidence.id(), CommunicationContracts.AuditDecision.APPROVED));
            assertTrue(handoff.approveVerifiedEvidence(WF, TASK, ref, approved.id(), communication, null));
            assertFalse(handoff.approveVerifiedEvidence(WF, TASK, ref, approved.id(), communication, null));
            assertEquals(approved.id(), handoff.approvedAuditId(WF, TASK).orElseThrow());
            assertEquals("b", handoff.claim(WF, 3, 60).orElseThrow().taskId());
        }
    }

    private static CommunicationContracts.Audit audit(String subject, CommunicationContracts.AuditDecision decision) {
        return CommunicationContracts.validate(new CommunicationContracts.Audit(
            "1.0", WF, subject, "quality-auditor", decision, 1, 3,
            decision == CommunicationContracts.AuditDecision.APPROVED ? List.of() : List.of("Evidence rejected"), List.of()));
    }
}
