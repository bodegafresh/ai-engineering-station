package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;

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

        var catalogIds = new HashSet<>(catalog.ids());
        var templateIds = new HashSet<>(registry.availableTemplates());

        var missingTemplates = new HashSet<>(catalogIds);
        missingTemplates.removeAll(templateIds);

        var extraTemplates = new HashSet<>(templateIds);
        extraTemplates.removeAll(catalogIds);

        assertTrue(
            missingTemplates.isEmpty() && extraTemplates.isEmpty(),
            "Faltan templates: " + missingTemplates
                + " | Templates adicionales: " + extraTemplates
        );

        assertFalse(catalog.ids().isEmpty(), "El catálogo no debe estar vacío");

        assertEquals(
            new HashSet<>(catalog.ids()),
            new HashSet<>(registry.availableTemplates()),
            "El catálogo y los templates deben contener los mismos especialistas"
        );

        for (String id : catalog.ids()) {
            String prompt = assertDoesNotThrow(
                () -> registry.render(id, variables()),
                "Error renderizando el especialista: " + id
            );

            assertFalse(prompt.isBlank(), "Prompt vacío: " + id);

            assertTrue(
                prompt.contains("Implementar validación de RUT chileno"),
                "El especialista " + id + " no incorpora el objetivo"
            );
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
