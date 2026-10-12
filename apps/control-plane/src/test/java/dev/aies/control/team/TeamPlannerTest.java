package dev.aies.control.team;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class TeamPlannerTest {
 @Test void marketTeamHasParallelWorkAndDependencies() {
  var p=new TeamPlanner().plan("Investigar mercado de colegios en Chile");
  assertEquals(5,p.steps().size());
  assertEquals(Set.of("market","geo","econ"),p.steps().get(3).dependsOn());
 }
 @Test void independentAndDependentAgentsRunWithApproval() throws Exception {
  try(var pool=Executors.newFixedThreadPool(4)) {
   var coordinator=new TeamCoordinator(pool);
   var plan=new TeamPlanner().plan("Investigar mercado de colegios");
   var result=coordinator.run(plan,(step,deps)->new TeamCoordinator.Result(step.id()+":"+deps.size(),List.of("fixture:"+step.id())),(step,r)->true);
   assertEquals("COMPLETED",result.status()); assertEquals(5,result.approved().size());
   assertEquals("stats:3",result.approved().get("stats").output());
  }
 }
 @Test void rejectionBlocksDependentAgents() throws Exception {
  try(var pool=Executors.newFixedThreadPool(4)) {
   var result=new TeamCoordinator(pool).run(new TeamPlanner().plan("Investigar mercado"),(step,deps)->new TeamCoordinator.Result("ok",List.of("fixture")),(step,r)->!step.id().equals("market"));
   assertEquals("PARTIAL_OR_BLOCKED",result.status());assertTrue(result.failures().containsKey("stats"));
  }
 }
 @Test void rejectsCycles() throws Exception {
  var a=new TeamPlan.Step("a","planner","a",Set.of("b"),Set.of());
  var b=new TeamPlan.Step("b","planner","b",Set.of("a"),Set.of());
  try(var pool=Executors.newFixedThreadPool(2)) {
   var report=new TeamCoordinator(pool).run(new TeamPlan("cycle",List.of(a,b)),(s,d)->new TeamCoordinator.Result("ok",List.of("fixture")),(s,r)->true);
   assertEquals("PARTIAL_OR_BLOCKED",report.status());
  }
 }
}
