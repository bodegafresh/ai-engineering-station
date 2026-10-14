package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistSelectionBridgeTest {
    @TempDir Path temp;
    private JsonObject request() {
        return new JsonObject().put("capabilities", new JsonArray().add("research"))
            .put("permittedTools", new JsonArray()).put("budget", 5);
    }
    @Test void legacyWireParityAndNoDatabaseCreation() throws Exception {
        Path db = temp.resolve("absent.sqlite");
        var bridge = new SpecialistSelectionBridge("false", db, new ObjectMapper());
        assertFalse(bridge.governedEnabled());
        assertEquals(new JsonObject().put("selected", true).put("specialistId", "researcher")
            .put("costUnits", 1), bridge.select(request()));
        assertFalse(Files.exists(db));
        assertEquals(new JsonObject().put("selected", false),
            bridge.select(request().put("budget", 0)));
    }
    @Test void governedMissingDatabaseNeverFallsBack() {
        var bridge = new SpecialistSelectionBridge("true", temp.resolve("absent.sqlite"), new ObjectMapper());
        assertTrue(bridge.governedEnabled());
        assertThrows(SQLException.class, () -> bridge.select(request()));
    }
    @Test void malformedInputsAndConfigurationRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpecialistSelectionBridge(
            "TRUE", temp.resolve("db"), new ObjectMapper()));
        var bridge = new SpecialistSelectionBridge("false", temp.resolve("db"), new ObjectMapper());
        assertThrows(IllegalArgumentException.class, () -> bridge.select(new JsonObject()
            .put("capabilities", new JsonArray().add(1))));
        assertThrows(IllegalArgumentException.class, () -> bridge.select(new JsonObject()
            .put("capabilities", new JsonArray().add("research")).put("budget", -1)));
    }
}
