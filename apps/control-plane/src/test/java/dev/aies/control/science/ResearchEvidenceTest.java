package dev.aies.control.science;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ResearchEvidenceTest {
    @Test void acceptsTraceableHttpsSource(){var s=new ResearchEvidence.Source("wf-1","exp-1","Study","https://example.org/study","2026-10-12T12:00:00Z","Excerpt", "a".repeat(64),"operator-provided",List.of("Not independently verified"));
        assertEquals(s,ResearchEvidence.validate(s));}
    @Test void rejectsUnsafeSchemes(){var s=new ResearchEvidence.Source("wf-1","exp-1","Study","file:///etc/passwd","now","Excerpt","a".repeat(64),"manual",List.of());
        assertThrows(IllegalArgumentException.class,()->ResearchEvidence.validate(s));}
}
