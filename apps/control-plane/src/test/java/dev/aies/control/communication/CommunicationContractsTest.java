package dev.aies.control.communication;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommunicationContractsTest {
    private CommunicationContracts.Message valid() {
        return new CommunicationContracts.Message("1.0","wf-001","task-001","orchestrator","analyst",
                CommunicationContracts.MessageType.TASK_REQUEST,"Analyze project request",
                List.of("artifact://wf-001/intake"),List.of("Identify edge cases"),
                List.of("Cite sources"),List.of("research-report"),
                List.of(new CommunicationContracts.Criterion("AC-001","a vague request exists",
                        "the specialist analyzes it","a documented report is produced")));
    }
    @Test void validatesAndCopiesMessage() {
        var result=CommunicationContracts.validate(valid());
        assertEquals("wf-001",result.workflowId());
        assertThrows(UnsupportedOperationException.class,()->result.instructions().add("unsafe"));
    }
    @Test void rejectsUnknownSchema() {
        var m=valid();
        assertThrows(IllegalArgumentException.class,()->CommunicationContracts.validate(
                new CommunicationContracts.Message("2.0",m.workflowId(),m.taskId(),m.producer(),
                        m.consumer(),m.type(),m.objective(),m.contextRefs(),m.instructions(),
                        m.constraints(),m.expectedOutputs(),m.acceptanceCriteria())));
    }
    @Test void rejectsPathTraversalIdentifiers() {
        assertThrows(IllegalArgumentException.class,()->CommunicationContracts.id("../outside"));
    }
    @Test void requiresBoundedAuditIterations() {
        assertThrows(IllegalArgumentException.class,()->CommunicationContracts.validate(
                new CommunicationContracts.Audit("1.0","wf-001","subject-001","auditor",
                        CommunicationContracts.AuditDecision.REVISION_REQUIRED,3,3,
                        List.of("Clarify scope"),List.of("Define boundaries"))));
        assertEquals(CommunicationContracts.AuditDecision.BLOCKED,CommunicationContracts.validate(
                new CommunicationContracts.Audit("1.0","wf-001","subject-001","auditor",
                        CommunicationContracts.AuditDecision.BLOCKED,3,3,
                        List.of("Iteration limit reached"),List.of("Escalate"))).decision());
    }
    @Test void rejectsUnsupportedArtifactType() {
        assertThrows(IllegalArgumentException.class,()->CommunicationContracts.validate(
                new CommunicationContracts.Artifact("1.0","wf-001","task-001","agent",
                        "report","application/x-shellscript","rm -rf /",List.of())));
    }
}
