package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistProfileRegistryTest {
    @Test void allCatalogProfilesExist() {
        var registry = new SpecialistProfileRegistry(new SpecialistCatalog(new ObjectMapper()));
        assertFalse(registry.ids().isEmpty());
        for (String id : registry.ids()) {
            String profile = registry.profile(id);
            assertTrue(profile.contains("## Criterios de aceptación"), id);
            assertTrue(profile.contains("## Contrato de salida"), id);
        }
    }
    @Test void rejectUnknownSpecialist() {
        var registry = new SpecialistProfileRegistry(new SpecialistCatalog(new ObjectMapper()));
        assertThrows(IllegalArgumentException.class, () -> registry.profile("../../secrets"));
    }
}
