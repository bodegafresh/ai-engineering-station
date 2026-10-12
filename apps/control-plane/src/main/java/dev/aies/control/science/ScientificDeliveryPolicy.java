package dev.aies.control.science;

import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import java.util.List;

/** P0.12: fail-closed gate; only immutable approved artifacts may be delivered. */
public final class ScientificDeliveryPolicy {
    private ScientificDeliveryPolicy() {}
    public static boolean approved(String recordId, List<CommunicationStore.StoredRecord> records) {
        var subject = records.stream().filter(r -> r.id().equals(recordId) && "ARTIFACT".equals(r.kind())).findFirst();
        if(subject.isEmpty()) return false;
        return records.stream().filter(r -> "AUDIT".equals(r.kind()))
            .map(r -> (CommunicationContracts.Audit) r.payload())
            .filter(a -> a.subjectId().equals(recordId) && a.workflowId().equals(subject.get().workflowId()))
            .reduce((a,b) -> b).map(a -> a.decision() == CommunicationContracts.AuditDecision.APPROVED).orElse(false);
    }
}
