package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import dev.aies.control.planning.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPlanAuditVerifierTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void exactApprovedAuditRequiredAndSurvivesReopen() throws Exception {
        var plan = ExecutionGraphTest.plan(ExecutionGraphTest.task("a"));
        var intake = new PlanningContracts.Intake("1.0", "wf-execution", "Implement API", "software",
            List.of(), List.of(), List.of(), List.of(), 10);
        var artifact = new CommunicationContracts.Artifact("1.0", "wf-execution",
            "planning-contract", "planning-controller", "planning-plan", "text/markdown", "Plan", List.of());
        String artifactId;
        try (var comm = open()) {
            artifactId = comm.append("ARTIFACT", "wf-execution", "planning-contract", artifact).id();
            var blocked = comm.append("AUDIT", "wf-execution", "planning-contract",
                audit(artifactId, CommunicationContracts.AuditDecision.BLOCKED)).id();
            var approvedWrongSubject = comm.append("AUDIT", "wf-execution", "planning-contract",
                audit("different-subject", CommunicationContracts.AuditDecision.APPROVED)).id();
            assertThrows(SecurityException.class, () -> ExecutionPlanAuditVerifier.requireApproved(
                snapshot(intake, plan, artifactId, blocked), comm));
            assertThrows(SecurityException.class, () -> ExecutionPlanAuditVerifier.requireApproved(
                snapshot(intake, plan, artifactId, approvedWrongSubject), comm));
            assertThrows(SecurityException.class, () -> ExecutionPlanAuditVerifier.requireApproved(
                snapshot(intake, plan, artifactId, "missing-audit"), comm));
        }
        try (var comm = open()) {
            var approved = comm.append("AUDIT", "wf-execution", "planning-contract",
                audit(artifactId, CommunicationContracts.AuditDecision.APPROVED)).id();
            ExecutionPlanAuditVerifier.requireApproved(snapshot(intake, plan, artifactId, approved), comm);
        }
    }

    private CommunicationStore open() throws Exception {
        return new CommunicationStore(root.resolve("communication.sqlite"), mapper);
    }
    private static CommunicationContracts.Audit audit(String subject, CommunicationContracts.AuditDecision decision) {
        return new CommunicationContracts.Audit("1.0", "wf-execution", subject, "auditor", decision,
            1, 3, List.of(), List.of());
    }
    private static PlanningStore.Snapshot snapshot(PlanningContracts.Intake intake, PlanningContracts.Plan plan,
                                                   String artifact, String audit) {
        return new PlanningStore.Snapshot("wf-execution", "READY_FOR_ASSIGNMENT", intake, plan,
            artifact, audit, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
    }
}
