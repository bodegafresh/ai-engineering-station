package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GovernedDocumentationGuardTest {
    private final GovernedSpecialistSelector guard = new GovernedSpecialistSelector(
        Path.of("/path-that-does-not-exist/aies-governed.sqlite"), new ObjectMapper(), true);

    @Test void legacyRejectsInjectedGovernedFields() throws Exception {
        assertNull(GovernedDocumentationGuard.require(new JsonObject().put("workflowId", "wf"), null));
        for (String key : new String[]{"governedSpecialistId", "governedSpecialistVersion", "governedCapability"}) {
            assertThrows(SecurityException.class, () -> GovernedDocumentationGuard.require(
                new JsonObject().put(key, "x"), null));
        }
    }
    @Test void governedRejectsMissingAndSpoofedIdentity() {
        assertThrows(SecurityException.class, () -> GovernedDocumentationGuard.require(new JsonObject(), guard));
        assertThrows(SecurityException.class, () -> GovernedDocumentationGuard.require(new JsonObject()
            .put("governedSpecialistId", "researcher").put("governedSpecialistVersion", "1")
            .put("governedCapability", "software"), guard));
        assertThrows(SecurityException.class, () -> GovernedDocumentationGuard.require(new JsonObject()
            .put("governedSpecialistId", "software-engineer").put("governedSpecialistVersion", "1")
            .put("governedCapability", "research"), guard));
    }
    @Test void governedFailsClosedWithoutRegistry() {
        var request = new JsonObject().put("governedSpecialistId", "software-engineer")
            .put("governedSpecialistVersion", "1.0.0").put("governedCapability", "software");
        assertThrows(Exception.class, () -> GovernedDocumentationGuard.require(request, guard));
    }
}
