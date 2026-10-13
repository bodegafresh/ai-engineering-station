package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

/** No information disclosure when the feature is disabled or unauthenticated. */
@QuarkusTest
class ExecutionMaterialAcceptanceResourceTest {
    @Test void anonymousAccessFailsClosed() {
        RestAssured.given().when()
            .get("/api/v1/execution/workflows/example/material-acceptance")
            .then().statusCode(404);
    }
    @Test void invalidTokenFailsClosed() {
        RestAssured.given().header("X-AIES-Workspace-Token", "invalid")
            .when().get("/api/v1/execution/workflows/example/material-acceptance")
            .then().statusCode(404);
    }
}
