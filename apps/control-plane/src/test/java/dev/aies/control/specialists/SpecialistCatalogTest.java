package dev.aies.control.specialists;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpecialistCatalogTest {
    @Test void deterministicCheapestEligible() {
        var expensive=new SpecialistCatalog.Specialist("expensive",Set.of("research"),Set.of(),1,8);
        var cheap=new SpecialistCatalog.Specialist("cheap",Set.of("research"),Set.of(),1,2);
        var selected=SpecialistCatalog.select(List.of(expensive,cheap),Set.of("research"),Set.of(),3);
        assertEquals("cheap",selected.orElseThrow().specialistId());
    }
    @Test void toolsNeverGrantedImplicitly() {
        var privileged=new SpecialistCatalog.Specialist("privileged",Set.of("research"),Set.of("network"),1,1);
        assertTrue(SpecialistCatalog.select(List.of(privileged),Set.of("research"),Set.of(),10).isEmpty());
    }
    @Test void rejectsBadBudgetAndCapacity() {
        assertThrows(IllegalArgumentException.class,()->new SpecialistCatalog.Specialist("x",Set.of("research"),Set.of(),0,1));
        assertThrows(IllegalArgumentException.class,()->SpecialistCatalog.select(List.of(),Set.of("research"),Set.of(),-1));
    }
}
