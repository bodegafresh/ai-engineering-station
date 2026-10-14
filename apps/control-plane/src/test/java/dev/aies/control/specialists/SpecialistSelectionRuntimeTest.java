package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistSelectionRuntimeTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private SpecialistSelectionContract.Request request() {
        return new SpecialistSelectionContract.Request(Set.of("research"), Set.of(), 5);
    }
    @Test void defaultUsesLegacyWithoutCreatingDatabase() throws Exception {
        Path db = temp.resolve("missing.sqlite");
        var runtime = SpecialistSelectionRuntime.fromConfiguration(null, db, mapper);
        assertFalse(runtime.governedEnabled());
        var result = runtime.select(request());
        assertEquals(SpecialistSelectionContract.Outcome.SELECTED, result.outcome());
        assertEquals("researcher", result.specialistId());
        assertEquals(SpecialistSelectionContract.Source.LEGACY_V1, result.source());
        assertFalse(Files.exists(db));
    }
    @Test void explicitFalseRemainsLegacy() throws Exception {
        var runtime = SpecialistSelectionRuntime.fromConfiguration("false", temp.resolve("none.db"), mapper);
        assertEquals(SpecialistSelectionContract.Source.LEGACY_V1, runtime.select(request()).source());
    }
    @Test void enabledMissingRegistryFailsClosedWithoutFallback() {
        var runtime = SpecialistSelectionRuntime.fromConfiguration("true", temp.resolve("none.db"), mapper);
        assertTrue(runtime.governedEnabled());
        assertThrows(SQLException.class, () -> runtime.select(request()));
    }
    @Test void malformedFlagsRejected() {
        for (String invalid : new String[]{"TRUE", "1", "yes", " false ", "true ", "on"}) {
            assertThrows(IllegalArgumentException.class, () ->
                SpecialistSelectionRuntime.fromConfiguration(invalid, temp.resolve("none.db"), mapper));
        }
    }
    @Test void nullRequestRejected() {
        var runtime = SpecialistSelectionRuntime.configure(false, temp.resolve("none.db"), mapper);
        assertThrows(NullPointerException.class, () -> runtime.select(null));
    }
}
