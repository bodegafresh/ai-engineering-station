package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP approval/revoke backed by a live governed lease and both SQLite stores. */
@QuarkusTest
@TestProfile(GovernedPolicyOperatorHttpPositiveTest.Enabled.class)
class GovernedPolicyOperatorHttpPositiveTest {
    private static final String TOKEN="0123456789abcdef0123456789abcdef";
    public static class Enabled implements QuarkusTestProfile {
        private static final Path ROOT=createRoot();
        private static Path createRoot() {
            try {return Files.createTempDirectory("aies-governed-http-positive-");}
            catch(Exception e) {throw new ExceptionInInitializerError(e);}
        }
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of(
                "aies.execution.governed-policy-operator.enabled","true",
                "aies.workspace-changes.token",TOKEN,
                "aies.agent-communications.data-root",ROOT.toString());
        }
    }
    @Test void approvalReplayRevocationAndAuditThroughActualHttp() throws Exception {
        Path root=Enabled.ROOT;
        Path handoffDb=root.resolve("execution-handoff.sqlite");
        Path policyDb=root.resolve("governed-tool-policies.sqlite");
        String token;
        try(var handoff=new ExecutionHandoffStore(handoffDb,new ObjectMapper())) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            token=handoff.claim("wf-execution",3,600).orElseThrow().leaseToken();
            handoff.bindGovernedAttempt(attempt(token));
        }
        var payload=new GovernedPolicyOperatorResource.Approval(
            "wf-execution","a",token,"software-engineer","1.0","software",
            "a".repeat(64),7,42,Instant.parse("2099-01-01T00:00:00Z"),
            List.of(new GovernedPolicyOperatorResource.Grant(
                "ollama","invoke-model","ollama:software-engineer")));
        for(int i=0;i<2;i++) {
            given().contentType("application/json").header("X-AIES-Workspace-Token",TOKEN)
                .body(payload).when().post("/api/v1/execution/governed-policies/approve")
                .then().statusCode(200);
        }
        try(var handoff=new ExecutionHandoffStore(handoffDb,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(policyDb)) {
            assertDoesNotThrow(()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
        }
        given().contentType("application/json").header("X-AIES-Workspace-Token",TOKEN)
            .when().post("/api/v1/execution/governed-policies/wf-execution/a/"+token+"/revoke")
            .then().statusCode(200);
        try(var handoff=new ExecutionHandoffStore(handoffDb,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(policyDb)) {
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
            assertTrue(policies.auditTrail("wf-execution","a",token).stream()
                .anyMatch(e->"REVOKED".equals(e.decision())));
        }
        given().contentType("application/json").header("X-AIES-Workspace-Token",TOKEN)
            .body(payload).when().post("/api/v1/execution/governed-policies/approve")
            .then().statusCode(409);
    }
    private static ExecutionHandoffStore.GovernedAttempt attempt(String token) {
        return new ExecutionHandoffStore.GovernedAttempt("wf-execution","a",token,
            "software-engineer","1.0","software","a".repeat(64),7);
    }
    private static GovernedToolResourcePolicy.Request request(String token) {
        return new GovernedToolResourcePolicy.Request("wf-execution","a",token,
            "software-engineer","1.0","ollama","invoke-model","ollama:software-engineer");
    }
}
