package dev.aies.control.execution;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.ReplyException;
import io.vertx.core.eventbus.ReplyFailure;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production documentation consumer started by Quarkus CDI, not reflection. */
@QuarkusTest
@TestProfile(WorkspaceDocumentationQuarkusLifecycleTest.DocumentationProfile.class)
class WorkspaceDocumentationQuarkusLifecycleTest {
    @Inject Vertx vertx;
    @Inject WorkspaceDocumentationEventBus consumer;

    public static final class DocumentationProfile implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of(
                "aies.execution.documentation.enabled", "true",
                "aies.specialists.governed-selection.enabled", "false",
                "aies.execution.documentation.workspace-root", ".",
                "aies.execution.documentation.timeout-seconds", "2"
            );
        }
    }

    private ReplyException rejection(JsonObject payload) throws Exception {
        var future = vertx.eventBus().<JsonObject>request(
            WorkspaceDocumentationEventBus.GENERATE, payload,
            new DeliveryOptions().setSendTimeout(3000)
        ).toCompletionStage().toCompletableFuture();
        ExecutionException exception = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(ReplyException.class, exception.getCause());
        return (ReplyException) exception.getCause();
    }

    @Test void cdiStartsTheProductionConsumerAndRejectsInvalidInput() throws Exception {
        assertNotNull(consumer, "Quarkus must inject the real consumer");
        ReplyException failure = rejection(new JsonObject());
        assertEquals(ReplyFailure.RECIPIENT_FAILURE, failure.failureType(),
            "The message must reach the consumer, not time out with NO_HANDLERS");
        assertEquals(422, failure.failureCode());
    }

    @Test void governanceFieldsFailClosedWhenGovernanceIsDisabled() throws Exception {
        ReplyException failure = rejection(new JsonObject()
            .put("governedSpecialistId", "software-engineer")
            .put("governedSpecialistVersion", "untrusted"));
        assertEquals(ReplyFailure.RECIPIENT_FAILURE, failure.failureType());
        assertEquals(422, failure.failureCode());
    }
}
