package dev.aies.control.specialists;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class SpecialistSelectionIntegrationTest {
 @Test void leastCostDeterministicAndNoUnauthorizedTools() {
  var pool=List.of(
   new SpecialistCatalog.Specialist("expensive",Set.of("research"),Set.of(),1,5),
   new SpecialistCatalog.Specialist("unsafe",Set.of("research"),Set.of("shell"),1,0),
   new SpecialistCatalog.Specialist("safe",Set.of("research"),Set.of(),1,1));
  var selected=SpecialistCatalog.select(pool,Set.of("research"),Set.of(),2).orElseThrow();
  assertEquals("safe",selected.specialistId());
  assertTrue(SpecialistCatalog.select(pool,Set.of("research"),Set.of(),0).isEmpty());
  assertTrue(SpecialistCatalog.select(pool,Set.of("missing"),Set.of("shell"),10).isEmpty());
 }
 @Test void rejectsInvalidCapacityAndInput() {
  assertThrows(IllegalArgumentException.class,()->new SpecialistCatalog.Specialist("bad",Set.of("research"),Set.of(),0,1));
  assertThrows(IllegalArgumentException.class,()->SpecialistCatalog.select(List.of(),Set.of(),Set.of(),1));
  assertThrows(IllegalArgumentException.class,()->SpecialistCatalog.select(List.of(),Set.of("research"),Set.of(),-1));
 }
}
