package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class GovernedPolicyOperatorHttpDisabledTest {
    @Test void disabledEndpointDoesNotDiscloseExistence() {
        given().contentType("application/json")
            .header("X-AIES-Workspace-Token","0123456789abcdef0123456789abcdef")
            .body("{}")
            .when().post("/api/v1/execution/governed-policies/approve")
            .then().statusCode(404);
        given().contentType("application/json")
            .header("X-AIES-Workspace-Token","0123456789abcdef0123456789abcdef")
            .when().post("/api/v1/execution/governed-policies/wf/task/attempt/revoke")
            .then().statusCode(404);
    }
}
