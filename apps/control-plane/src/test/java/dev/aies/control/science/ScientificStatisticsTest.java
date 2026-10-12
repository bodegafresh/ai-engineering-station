package dev.aies.control.science;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScientificStatisticsTest {
    @Test void wilsonBoundsAreConservativeAtSmallSamples() {
        var result=ScientificStatistics.wilson95(8,10);
        assertEquals(.8,result.estimate(),1e-12);
        assertTrue(result.lower()<.5);
        assertTrue(result.upper()>.9);
        assertThrows(IllegalArgumentException.class,()->ScientificStatistics.wilson95(11,10));
    }
    @Test void littlesLawUsesConsistentUnits() {
        assertEquals(12,ScientificStatistics.expectedWorkInProgress(3,4),1e-12);
    }
}
