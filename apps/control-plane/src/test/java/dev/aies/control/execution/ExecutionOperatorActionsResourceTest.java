package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

/** Default-off operator commands never disclose workflow state or invoke consumers. */
@QuarkusTest
class ExecutionOperatorActionsResourceTest {
    @Test void runOneRejectsAnonymousAndInvalidCredentials() {
        RestAssured.given().when().post("/api/v1/execution/workflows/sample/operator/run-one")
            .then().statusCode(404);
        RestAssured.given().header("X-AIES-Workspace-Token", "invalid")
            .when().post("/api/v1/execution/workflows/sample/operator/run-one")
            .then().statusCode(404);
    }
    @Test void reviewRejectsAnonymousAndInvalidCredentials() {
        RestAssured.given().when().post("/api/v1/execution/workflows/sample/operator/tasks/task-1/review")
            .then().statusCode(404);
        RestAssured.given().header("X-AIES-Workspace-Token", "invalid")
            .when().post("/api/v1/execution/workflows/sample/operator/tasks/task-1/review")
            .then().statusCode(404);
    }
}
