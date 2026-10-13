package dev.aies.control.planning;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static io.restassured.RestAssured.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestProfile(PlanningRestIntegrationTest.Profile.class)
class PlanningRestIntegrationTest {
 static final String TOKEN="integration-test-token-1234567890-1234567890";
 public static class Profile implements QuarkusTestProfile {
  public Map<String,String> getConfigOverrides(){
   return Map.of(
    "aies.workspace-changes.token",TOKEN,
    "aies.planning.enabled","true",
    "aies.agent-communications.enabled","true",
    "aies.specialists.enabled","true",
    "aies.semantic-audit.enabled","false",
    "aies.planning.poll-ms","250",
    "aies.agent-communications.data-root",
       System.getProperty("java.io.tmpdir")+"/aies-planning-rest-"+UUID.randomUUID());
  }
 }
 @Test void unauthorizedCannotCreate(){
  given().contentType(ContentType.JSON).body("{}").when().post("/api/v1/planning/workflows")
   .then().statusCode(404);
 }
 @Test void fullRestEventBusAuditPersistence()throws Exception {
  String id="wf-"+UUID.randomUUID();
  var req=new PlanningContracts.Requirement("r1","Create task via API","src1",
   PlanningContracts.Verification.TEST,"valid JSON","POST /tasks","HTTP 201 persisted",
   "software",List.of(),false);
  var intake=new PlanningContracts.Intake("1.0",id,"Build a task management endpoint","software",
   List.of(new PlanningContracts.Source("src1","artifact://spec/task","a".repeat(64),"Task API request")),
   List.of(req),List.of("No remote dependencies"),List.of(),10);
  given().header("X-AIES-Workspace-Token",TOKEN).contentType(ContentType.JSON).body(intake)
   .when().post("/api/v1/planning/workflows").then().statusCode(201).body("status",equalTo("RECEIVED"));
  String state="RECEIVED";
  for(int i=0;i<100;i++){
   state=given().header("X-AIES-Workspace-Token",TOKEN).get("/api/v1/planning/workflows/"+id)
    .then().statusCode(200).extract().path("status");
   if(state.equals("READY_FOR_ASSIGNMENT")||state.equals("REVISION_REQUIRED")||state.equals("BLOCKED"))break;
   Thread.sleep(100);
  }
  assertEquals("READY_FOR_ASSIGNMENT",state,"Structural-only audit should approve a complete plan");
  given().header("X-AIES-Workspace-Token",TOKEN).get("/api/v1/planning/workflows/"+id)
   .then().body("plan.tasks.size()",equalTo(1))
   .body("plan.tasks[0].specialistId",equalTo("software-engineer"))
   .body("artifactRecordId",notNullValue())
   .body("auditRecordId",notNullValue());
 }
}
