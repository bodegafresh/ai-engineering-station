package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import static org.hamcrest.Matchers.*;

/** Default-off operator ingress must not disclose workflow existence or data. */
@QuarkusTest
class ExecutionAcceptanceResourceTest {
    @Test void rejectsAnonymousAccessByDefault() {
        RestAssured.given()
            .when().get("/api/v1/execution/workflows/example/acceptance")
            .then().statusCode(404);
    }
    @Test void rejectsInvalidOperatorCredentialByDefault() {
        RestAssured.given()
            .header("X-AIES-Workspace-Token", "not-authorized")
            .when().get("/api/v1/execution/workflows/example/acceptance")
            .then().statusCode(404);
    }
}
