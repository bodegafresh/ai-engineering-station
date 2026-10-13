package dev.aies.control.methodology;

import dev.aies.control.communication.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MethodologyAuditIntegrationTest {
    private CommunicationStore.StoredRecord artifact(String kind) {
        return new CommunicationStore.StoredRecord("id","wf","task","ARTIFACT","1.0","hash","now",
            new CommunicationContracts.Artifact("1.0","wf","task","planner",kind,"text/markdown","# Plan",List.of()));
    }
    @Test void universalPlanUsesRequirementsRubric() {
        var stage=AuditStagePolicy.classify(artifact("planning-plan"));
        assertEquals(AuditStagePolicy.Stage.REQUIREMENTS_PLAN,stage);
        assertTrue(AuditStagePolicy.rubric(stage).contains("source traceability"));
        assertTrue(AuditStagePolicy.rubric(stage).contains("Do not demand null hypotheses"));
    }
    @Test void scientificPlanRetainsScientificRubric() {
        var stage=AuditStagePolicy.classify(artifact("scientific-plan"));
        assertEquals(AuditStagePolicy.Stage.PLAN,stage);
        assertTrue(AuditStagePolicy.rubric(stage).contains("falsifiable hypotheses"));
    }
}
