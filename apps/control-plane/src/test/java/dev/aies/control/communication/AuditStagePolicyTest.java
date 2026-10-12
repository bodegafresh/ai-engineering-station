package dev.aies.control.communication;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AuditStagePolicyTest {
    private CommunicationStore.StoredRecord record(String kind) {
        return new CommunicationStore.StoredRecord("id","wf","task","ARTIFACT","1.0","hash","now",
            new CommunicationContracts.Artifact("1.0","wf","task","factory",kind,"text/markdown","# Plan\nContent",List.of()));
    }
    @Test void scientificPlanDoesNotRequireResults() {
        var stage=AuditStagePolicy.classify(record("scientific-plan"));
        assertEquals(AuditStagePolicy.Stage.PLAN,stage);
        assertTrue(AuditStagePolicy.rubric(stage).contains("DO NOT require experimental results"));
    }
    @Test void reportRequiresObservedEvidence() {
        var stage=AuditStagePolicy.classify(record("experiment-results"));
        assertEquals(AuditStagePolicy.Stage.EXPERIMENT_REPORT,stage);
        assertTrue(AuditStagePolicy.rubric(stage).contains("actual observed data"));
    }
}
