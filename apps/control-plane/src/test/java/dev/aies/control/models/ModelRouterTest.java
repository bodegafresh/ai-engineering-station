package dev.aies.control.models;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelRouterTest {
    private ModelRoutingConfig config() {
        return ModelRoutingConfigLoader.loadClasspath("models/routing.json");
    }

    @Test void everySpecialistHasOrderedCandidates() {
        var config = config();
        assertEquals(27, config.specialists().size());
        for (var entry : config.specialists().entrySet()) {
            assertFalse(entry.getValue().isEmpty(), entry.getKey());
            assertTrue(config.models().keySet().containsAll(entry.getValue()), entry.getKey());
        }
    }

    @Test void prioritizesAvailableModel() {
        var router = new ModelRouter(config(), (id, model) -> true, false, Map.of());
        assertEquals("local-code", router.select("backend-developer").configurationId());
    }

    @Test void usesGlobalFallback() {
        var router = new ModelRouter(config(), (id, model) -> id.equals("local-default"), false, Map.of());
        var selected = router.select("architect");
        assertEquals("local-default", selected.configurationId());
        assertTrue(selected.fallback());
    }

    @Test void failsClosedWhenNoModelAvailable() {
        var router = new ModelRouter(config(), (id, model) -> false, false, Map.of());
        assertThrows(ModelRouter.NoAvailableModelException.class, () -> router.select("architect"));
    }

    @Test void neverEnablesCloudWithoutOptIn() {
        var cfg = config();
        var custom = new ModelRoutingConfig("cloud-optional", cfg.models(),
                Map.of("architect", List.of("cloud-optional")));
        var router = new ModelRouter(custom, (id, model) -> true, false,
                Map.of("AIES_CLOUD_API_KEY", "dummy"));
        assertThrows(ModelRouter.NoAvailableModelException.class, () -> router.select("architect"));
    }

    @Test void unknownSpecialistUsesDefault() {
        var router = new ModelRouter(config(), (id, model) -> true, false, Map.of());
        assertEquals("local-default", router.select("custom-specialist").configurationId());
    }
}
