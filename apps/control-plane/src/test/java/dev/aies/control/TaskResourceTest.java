package dev.aies.control;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
@QuarkusTest
class TaskResourceTest {
    @Test void createsAndRetrievesTask() {
        String id=given().contentType("application/json").body("""
                {"objective":"Create an architecture decision record"}
                """)
            .when().post("/api/v1/tasks").then().statusCode(201).body("status",anyOf(is("PENDING"),is("COMPLETED")))
            .extract().path("id");
        given().when().get("/api/v1/tasks/"+id).then().statusCode(200).body("objective",is("Create an architecture decision record"));
    }
    @Test void rejectsInvalidObjective() {
        given().contentType("application/json").body("""
    {"objective":""}
    """).when().post("/api/v1/tasks").then().statusCode(400);
    }
}
