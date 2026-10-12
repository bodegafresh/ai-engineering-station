package dev.aies.control.science;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScientificRetrospectiveTest {
    @Test void emptyIsHonest(){var r=ScientificRetrospective.calculate("wf-1",List.of(),List.of());assertEquals(0,r.observationCount());assertTrue(r.experiments().isEmpty());}
    @Test void estimatesWithWilsonAndCost(){
        var observations=List.of(
            new ResearchEvidenceStore.Observation("a","wf-1","exp-1",true,3,4,"ok","now"),
            new ResearchEvidenceStore.Observation("b","wf-1","exp-1",false,2,6,"failed","now"));
        var result=ScientificRetrospective.calculate("wf-1",List.of(),observations).experiments().getFirst();
        assertEquals(2,result.trials());assertEquals(.5,result.successRate(),1e-12);
        assertEquals(5,result.costPerSuccess(),1e-12);assertEquals(5,result.meanDurationSeconds(),1e-12);
        assertTrue(result.wilsonLower95()<.5);assertTrue(result.wilsonUpper95()>.5);
        assertEquals("INSUFFICIENT_SAMPLE",result.recommendation());
    }
    @Test void rejectsCrossWorkflow(){var o=new ResearchEvidenceStore.Observation("a","other","exp",true,1,1,"ok","now");
        assertThrows(IllegalArgumentException.class,()->ScientificRetrospective.calculate("wf-1",List.of(),List.of(o)));}
}
