package dev.aies.control.planning;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RequirementsAssessmentTest {
    private PlanningContracts.Requirement req(String id,String statement) {
        return new PlanningContracts.Requirement(id,statement,"src1",PlanningContracts.Verification.TEST,
            "given","when","then","software",List.of(),false);
    }
    private PlanningContracts.Intake intake(PlanningContracts.Requirement... reqs) {
        return new PlanningContracts.Intake("1.0","wf-a","Build API","software",
            List.of(new PlanningContracts.Source("src1","artifact://spec/1","a".repeat(64),"Source")),
            List.of(reqs),List.of(),List.of(),10);
    }
    @Test void detectsNormalizedDuplicates() {
        var result=RequirementsAssessment.assess(intake(req("r1","Must expose API"),req("r2","  MUST  expose  API ")));
        assertFalse(result.approved());
        assertEquals("DUPLICATE_REQUIREMENT",result.findings().get(0).code());
    }
    @Test void detectsExplicitNegation() {
        var result=RequirementsAssessment.assess(intake(req("r1","System must store secrets"),req("r2","System must not store secrets")));
        assertTrue(result.findings().stream().anyMatch(f->f.code().equals("CONTRADICTORY_REQUIREMENTS")));
    }
    @Test void keepsProvenanceAndVerifiability() {
        var result=RequirementsAssessment.assess(intake(req("r1","System must respond")));
        assertTrue(result.approved());
        assertEquals(1.0,result.traceability());
        assertEquals(1.0,result.verifiability());
    }
}
