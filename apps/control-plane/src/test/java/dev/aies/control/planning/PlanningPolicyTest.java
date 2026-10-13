package dev.aies.control.planning;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlanningPolicyTest {
 static PlanningContracts.Source source(){
  return new PlanningContracts.Source("src1","artifact://spec/1","a".repeat(64),"Original requirements");
 }
 static PlanningContracts.Requirement req(String id,List<String> deps){
  return new PlanningContracts.Requirement(id,"API must return a persisted identifier","src1",
   PlanningContracts.Verification.TEST,"valid request","POST /tasks","HTTP 201 and stored record",
   "software",deps,false);
 }
 static PlanningContracts.Intake intake(List<PlanningContracts.Requirement> requirements,List<String> questions,int budget){
  return new PlanningContracts.Intake("1.0","wf-p0","Implement task API","software",List.of(source()),
   requirements,List.of("No remote network"),questions,budget);
 }
 static PlanningContracts.Task task(PlanningContracts.Requirement r,int cost){
  return new PlanningContracts.Task("task-"+r.id(),r.id(),r.capability(),
   r.dependsOn().stream().map(d->"task-"+d).toList(),"software-engineer",cost,
   r.statement(),r.given(),r.when(),r.then(),r.verification());
 }
 @Test void acceptsTraceablePlanAndDAG(){
  var r1=req("r1",List.of());var r2=req("r2",List.of("r1"));
  var in=intake(List.of(r1,r2),List.of(),10);
  assertTrue(PlanningPolicy.review(in).approved());
  var plan=PlanningPolicy.build(in,Map.of("r1",task(r1,3),"r2",task(r2,3)));
  assertEquals("AUDIT_REQUIRED",plan.status());
  assertEquals(List.of("task-r1"),plan.tasks().get(1).dependencies());
  assertEquals(6,plan.estimatedCostUnits());
 }
 @Test void blocksCycles(){
  var in=intake(List.of(req("r1",List.of("r2")),req("r2",List.of("r1"))),List.of(),10);
  assertFalse(PlanningPolicy.review(in).approved());
 }
 @Test void refusesUntraceableSources(){
  var r=new PlanningContracts.Requirement("r1","some requirement","unknown",PlanningContracts.Verification.TEST,
   "given","when","then","software",List.of(),false);
  assertThrows(IllegalArgumentException.class,()->PlanningContracts.validate(intake(List.of(r),List.of(),10)));
 }
 @Test void questionsStopProgression(){
  var r=req("r1",List.of());
  assertEquals("NEEDS_CLARIFICATION",PlanningPolicy.build(intake(List.of(r),List.of("Who is the user?"),10),
   Map.of("r1",task(r,3))).status());
 }
 @Test void budgetStopsProgression(){
  var r=req("r1",List.of());
  assertEquals("BLOCKED_BUDGET",PlanningPolicy.build(intake(List.of(r),List.of(),2),
   Map.of("r1",task(r,3))).status());
 }
 @Test void refusesInvalidAcceptanceAndDependencies(){
  var r=req("r1",List.of("missing"));
  assertThrows(IllegalArgumentException.class,()->PlanningPolicy.review(intake(List.of(r),List.of(),10)));
  var invalid=new PlanningContracts.Requirement("r1","something","src1",PlanningContracts.Verification.TEST,
   "given","when"," ","software",List.of(),false);
  assertThrows(IllegalArgumentException.class,()->PlanningContracts.validate(intake(List.of(invalid),List.of(),10)));
 }
 @Test void rejectsInvalidDigestAndUri(){
  var r=req("r1",List.of());
  var bad=new PlanningContracts.Intake("1.0","wf-p0","objective","software",
   List.of(new PlanningContracts.Source("src1","http://unsafe","abc","excerpt")),
   List.of(r),List.of(),List.of(),10);
  assertThrows(IllegalArgumentException.class,()->PlanningContracts.validate(bad));
 }
 @Test void criticalRequirementNeedsObjectiveVerification(){
  var r=req("r1",List.of());
  var critical=new PlanningContracts.Requirement(r.id(),r.statement(),r.sourceId(),
   PlanningContracts.Verification.INSPECTION,r.given(),r.when(),r.then(),r.capability(),r.dependsOn(),true);
  assertFalse(PlanningPolicy.review(intake(List.of(critical),List.of(),10)).approved());
 }
}
