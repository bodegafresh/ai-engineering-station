package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ExecutionRecoveryResourceTest {
    @Test void anonymousIsHiddenByDefault() {
        RestAssured.get("/api/v1/execution/workflows/example/recovery").then().statusCode(404);
    }
    @Test void invalidTokenIsHiddenByDefault() {
        RestAssured.given().header("X-AIES-Workspace-Token", "invalid")
            .get("/api/v1/execution/workflows/example/recovery").then().statusCode(404);
    }
}
