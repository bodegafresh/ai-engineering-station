package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PromptIntegrationTest {

    private final VelocityPromptTemplateRegistry registry =
            new VelocityPromptTemplateRegistry();

    private final SpecialistCatalog catalog =
            new SpecialistCatalog(new ObjectMapper());

    private Map<String, String> variables() {
        return Map.of(
            "objective", "Implementar validación de RUT chileno",
            "projectContext", "Java 21, Quarkus, arquitectura limpia",
            "constraints", "No modificar APIs públicas",
            "acceptanceCriteria", "Tests unitarios y casos límite",
            "outputContract", "JSON con archivos y evidencias"
        );
    }

    @Test
    void allSpecialistsHaveRenderableTemplates() {
        assertEquals(15, registry.availableTemplates().size());

        for (String id : registry.availableTemplates()) {
            assertNotNull(catalog.get(id));

            String prompt = registry.render(id, variables());

            assertFalse(prompt.isBlank(), id);
            assertTrue(prompt.contains(
                "Implementar validación de RUT chileno"
            ), id);
        }
    }

    @Test
    void rejectsUnknownTemplate() {
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.render("../secrets", variables())
        );
    }

    @Test
    void rejectsIncompleteContext() {
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.render(
                "architect",
                Map.of("objective", "Diseñar API")
            )
        );
    }

    @Test
    void catalogHasValidIterationLimits() {
        for (String id : catalog.ids()) {
            var specialist = catalog.get(id);

            assertTrue(specialist.maxIterations() >= 1);
            assertTrue(specialist.maxIterations() <= 20);
        }
    }
}
