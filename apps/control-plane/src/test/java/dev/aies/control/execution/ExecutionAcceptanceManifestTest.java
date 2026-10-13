package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionAcceptanceManifestTest {
    @TempDir Path dir;
    @Test void incompleteUntilExactApprovedAuditAndSurvivesReopen() throws Exception {
        var mapper = new ObjectMapper();
        var manifest = new ExecutionAcceptanceManifest(dir, mapper);
        String wf = "wf-execution", task = "a";
        try (var handoff = new ExecutionHandoffStore(dir.resolve("execution-handoff.sqlite"), mapper);
             var communication = new CommunicationStore(dir.resolve("communication.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            assertEquals("INCOMPLETE", manifest.inspect(wf).acceptanceStatus());
            var leased = handoff.claim(wf, 3, 60).orElseThrow();
            var artifact = communication.append("ARTIFACT", wf, task,
                new CommunicationContracts.Artifact("1.0", wf, task, "software-engineer", "execution-result",
                    "text/plain", "Observed output", List.of()));
            assertTrue(handoff.stageEvidence(wf, task, leased.leaseToken(), artifact.id()));
            assertEquals("INCOMPLETE", manifest.inspect(wf).acceptanceStatus());
            var audit = communication.append("AUDIT", wf, task,
                new CommunicationContracts.Audit("1.0", wf, artifact.id(), "quality-auditor",
                    CommunicationContracts.AuditDecision.APPROVED, 1, 3, List.of(), List.of()));
            assertTrue(handoff.approveVerifiedEvidence(wf, task,
                "artifact://communication/" + artifact.id(), audit.id(), communication, null));
        }
        var result = manifest.inspect(wf);
        assertEquals("VERIFIED", result.acceptanceStatus());
        assertEquals(1, result.tasks().size());
        assertEquals("APPROVED", result.tasks().get(0).auditDecision());
        assertNotNull(result.tasks().get(0).artifactSha256());
        assertNotNull(result.tasks().get(0).auditSha256());
        assertThrows(IllegalArgumentException.class, () -> manifest.inspect("unknown"));
    }
}
