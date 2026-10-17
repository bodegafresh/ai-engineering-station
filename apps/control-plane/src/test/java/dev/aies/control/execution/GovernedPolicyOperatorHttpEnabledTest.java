package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(GovernedPolicyOperatorHttpEnabledTest.Enabled.class)
class GovernedPolicyOperatorHttpEnabledTest {
    static final String TOKEN="0123456789abcdef0123456789abcdef";
    public static class Enabled implements QuarkusTestProfile {
        @Override public Map<String,String> getConfigOverrides() {
            return Map.of(
                "aies.execution.governed-policy-operator.enabled","true",
                "aies.workspace-changes.token",TOKEN);
        }
    }
    @Test void invalidAndMissingTokensCannotApproveOrRevoke() {
        for(String endpoint : new String[]{
            "/api/v1/execution/governed-policies/approve",
            "/api/v1/execution/governed-policies/wf/task/attempt/revoke"}) {
            given().contentType("application/json").body("{}")
                .when().post(endpoint).then().statusCode(404);
            given().contentType("application/json")
                .header("X-AIES-Workspace-Token","wrong").body("{}")
                .when().post(endpoint).then().statusCode(404);
        }
    }
    @Test void authenticatedInvalidApprovalAndUnknownRevocationAreRejected() {
        given().contentType("application/json")
            .header("X-AIES-Workspace-Token",TOKEN).body("{}")
            .when().post("/api/v1/execution/governed-policies/approve")
            .then().statusCode(409);
        given().contentType("application/json")
            .header("X-AIES-Workspace-Token",TOKEN)
            .when().post("/api/v1/execution/governed-policies/nonexistent/task/attempt/revoke")
            .then().statusCode(409);
    }
}
