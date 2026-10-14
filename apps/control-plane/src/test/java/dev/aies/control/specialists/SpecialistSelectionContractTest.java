package dev.aies.control.specialists;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistSelectionContractTest {
    @Test void legacyV1Parity() {
        var catalog = List.of(
            new SpecialistCatalog.Specialist("researcher",Set.of("research"),Set.of(),2,1),
            new SpecialistCatalog.Specialist("requirements-auditor",Set.of("requirements","audit"),Set.of(),1,2),
            new SpecialistCatalog.Specialist("software-engineer",Set.of("software","implementation"),Set.of(),1,3),
            new SpecialistCatalog.Specialist("experiment-analyst",Set.of("statistics","experiments"),Set.of(),1,2));
        for (var capability : List.of("research","requirements","audit","software","implementation","statistics","experiments","unknown"))
            for (int budget : List.of(0,1,2,3,10)) {
                var req = new SpecialistSelectionContract.Request(Set.of(capability),Set.of(),budget);
                var actual = SpecialistSelectionContract.select(req,SpecialistSelectionContract.legacyV1());
                var expected = SpecialistCatalog.select(catalog,req.capabilities(),req.permittedTools(),budget);
                assertEquals(expected.isPresent(),actual.outcome()==SpecialistSelectionContract.Outcome.SELECTED);
                expected.ifPresent(e -> { assertEquals(e.specialistId(),actual.specialistId()); assertEquals(e.costUnits(),actual.costUnits()); });
            }
    }
    @Test void noImplicitToolGrant() {
        var candidate = new SpecialistSelectionContract.Candidate("restricted","1.0.0",Set.of("research"),Set.of("network"),1,1,SpecialistSelectionContract.Source.GOVERNED_V2);
        var req = new SpecialistSelectionContract.Request(Set.of("research"),Set.of(),10);
        assertEquals(SpecialistSelectionContract.Outcome.NO_MATCH,SpecialistSelectionContract.select(req,List.of(candidate)).outcome());
        assertEquals(SpecialistSelectionContract.Outcome.SELECTED,SpecialistSelectionContract.select(new SpecialistSelectionContract.Request(Set.of("research"),Set.of("network"),10),List.of(candidate)).outcome());
    }
    @Test void deterministicAndDefensive() {
        var a = new SpecialistSelectionContract.Candidate("alpha","1.0.0",Set.of("research"),Set.of(),1,1,SpecialistSelectionContract.Source.LEGACY_V1);
        var z = new SpecialistSelectionContract.Candidate("zeta","1.0.0",Set.of("research"),Set.of(),1,1,SpecialistSelectionContract.Source.LEGACY_V1);
        assertEquals("alpha",SpecialistSelectionContract.select(new SpecialistSelectionContract.Request(Set.of("research"),Set.of(),1),List.of(z,a)).specialistId());
        assertThrows(IllegalArgumentException.class,()->new SpecialistSelectionContract.Request(Set.of(),Set.of(),1));
        assertThrows(IllegalArgumentException.class,()->new SpecialistSelectionContract.Request(Set.of("research"),Set.of(),-1));
        assertThrows(IllegalArgumentException.class,()->new SpecialistSelectionContract.Decision(SpecialistSelectionContract.Outcome.NO_MATCH,"alpha","1.0.0",SpecialistSelectionContract.Source.LEGACY_V1,1));
    }
}
