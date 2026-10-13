package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import dev.aies.control.planning.PlanningStore;
import java.util.Objects;

/** Fail-closed import policy: never trust a planning status string without its immutable audit chain. */
public final class ExecutionPlanAuditVerifier {
    private ExecutionPlanAuditVerifier() {}

    public static void requireApproved(PlanningStore.Snapshot snapshot, CommunicationStore communication) throws Exception {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(communication);
        if (!"READY_FOR_ASSIGNMENT".equals(snapshot.status()) || snapshot.plan() == null
                || snapshot.artifactRecordId() == null || snapshot.auditRecordId() == null
                || !snapshot.workflowId().equals(snapshot.plan().workflowId()))
            throw new SecurityException("Planning state is not approved");

        var artifact = communication.find(snapshot.artifactRecordId())
            .orElseThrow(() -> new SecurityException("Planning artifact missing"));
        if (!"ARTIFACT".equals(artifact.kind())
                || !snapshot.workflowId().equals(artifact.workflowId())
                || !"planning-contract".equals(artifact.taskId())
                || !(artifact.payload() instanceof CommunicationContracts.Artifact a)
                || !snapshot.workflowId().equals(a.workflowId())
                || !"planning-contract".equals(a.taskId())
                || !"planning-plan".equals(a.kind()))
            throw new SecurityException("Planning artifact correlation invalid");

        var audit = communication.find(snapshot.auditRecordId())
            .orElseThrow(() -> new SecurityException("Planning audit missing"));
        if (!"AUDIT".equals(audit.kind())
                || !snapshot.workflowId().equals(audit.workflowId())
                || !"planning-contract".equals(audit.taskId())
                || !(audit.payload() instanceof CommunicationContracts.Audit value)
                || !snapshot.workflowId().equals(value.workflowId())
                || !snapshot.artifactRecordId().equals(value.subjectId())
                || value.decision() != CommunicationContracts.AuditDecision.APPROVED)
            throw new SecurityException("Planning audit is not an exact approval");
    }
}
