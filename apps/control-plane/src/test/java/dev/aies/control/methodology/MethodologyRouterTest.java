package dev.aies.control.methodology;

import dev.aies.control.planning.PlanningContracts;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MethodologyRouterTest {
    private PlanningContracts.Intake intake(String domain,String capability) {
        return new PlanningContracts.Intake("1.0","wf-a","Implement and verify service",domain,
            List.of(new PlanningContracts.Source("s1","artifact://requirements/1","a".repeat(64),"Source")),
            List.of(new PlanningContracts.Requirement("r1","Service responds with HTTP 200","s1",
                PlanningContracts.Verification.TEST,"valid request","GET /","HTTP 200",capability,List.of(),false)),
            List.of(),List.of(),10);
    }
    @Test void softwareNeverRequiresScientificMethod() {
        assertEquals(MethodologyRouter.Methodology.SOFTWARE_ENGINEERING,MethodologyRouter.select(intake("software","software")).methodology());
    }
    @Test void scienceIsExplicit() {
        assertEquals(MethodologyRouter.Methodology.SCIENTIFIC_RESEARCH,MethodologyRouter.select(intake("science","experiments")).methodology());
    }
    @Test void unknownDomainFailsClosed() {
        var decision=MethodologyRouter.select(intake("other","other"));
        assertEquals(MethodologyRouter.Methodology.GENERAL,decision.methodology());
        assertTrue(decision.needsClarification());
    }
    @Test void declaredCapabilityCanSelectMethodology() {
        assertEquals(MethodologyRouter.Methodology.KNOWLEDGE_RESEARCH,MethodologyRouter.select(intake("other","research")).methodology());
    }
}
