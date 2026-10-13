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
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionMaterialAcceptanceTest {
    @TempDir Path root;

    @Test void noMaterialReferenceCannotClaimMaterialVerification() throws Exception {
        var mapper = new ObjectMapper();
        var checker = new ExecutionMaterialAcceptance(root, root, mapper);
        try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
             var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            var work = handoff.claim("wf-execution", 3, 60).orElseThrow();
            var artifact = comm.append("ARTIFACT", "wf-execution", "a", new CommunicationContracts.Artifact(
                "1.0", "wf-execution", "a", "software-engineer", "execution-result", "text/plain", "output", List.of()));
            assertTrue(handoff.stageEvidence("wf-execution", "a", work.leaseToken(), artifact.id()));
            var audit = comm.append("AUDIT", "wf-execution", "a", new CommunicationContracts.Audit(
                "1.0", "wf-execution", artifact.id(), "quality-auditor",
                CommunicationContracts.AuditDecision.APPROVED, 1, 3, List.of(), List.of()));
            assertTrue(handoff.approveVerifiedEvidence("wf-execution", "a", "artifact://communication/" + artifact.id(), audit.id(), comm, null));
        }
        assertEquals("VERIFIED", new ExecutionAcceptanceManifest(root, mapper).inspect("wf-execution").acceptanceStatus());
        var result = checker.inspect("wf-execution");
        assertEquals("INCOMPLETE", result.status());
        assertEquals("NOT_APPLICABLE", result.checks().get(0).status());
    }

    @Test void materialFileMustStillMatchExactCanonicalBytes() throws Exception {
        var mapper = new ObjectMapper();
        Path docs = Files.createDirectories(root.resolve(".aies/generated-docs"));
        Path file = docs.resolve("result.md");
        String content = "Verified documentation";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        var checker = new ExecutionMaterialAcceptance(root, root, mapper);
        try (var handoff = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper);
             var comm = new CommunicationStore(root.resolve("communication.sqlite"), mapper)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            var work = handoff.claim("wf-execution", 3, 60).orElseThrow();
            var artifact = comm.append("ARTIFACT", "wf-execution", "a", new CommunicationContracts.Artifact(
                "1.0", "wf-execution", "a", "software-engineer", "execution-result", "text/markdown", content,
                List.of(WorkspaceMaterialEvidence.reference(".aies/generated-docs/result.md", hash))));
            assertTrue(handoff.stageEvidence("wf-execution", "a", work.leaseToken(), artifact.id()));
            var audit = comm.append("AUDIT", "wf-execution", "a", new CommunicationContracts.Audit(
                "1.0", "wf-execution", artifact.id(), "quality-auditor",
                CommunicationContracts.AuditDecision.APPROVED, 1, 3, List.of(), List.of()));
            assertTrue(handoff.approveVerifiedEvidence("wf-execution", "a", "artifact://communication/" + artifact.id(), audit.id(), comm, root));
        }
        assertEquals("MATERIAL_VERIFIED", checker.inspect("wf-execution").status());
        Files.writeString(file, "tampered", StandardCharsets.UTF_8);
        assertThrows(SecurityException.class, () -> checker.inspect("wf-execution"));
    }
}
