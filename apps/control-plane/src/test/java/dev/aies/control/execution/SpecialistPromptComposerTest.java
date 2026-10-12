package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.prompts.*;
import dev.aies.control.routing.CapabilityRouter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistPromptComposerTest {
    private final SpecialistCatalog catalog = new SpecialistCatalog(new ObjectMapper());
    private final SpecialistPromptComposer composer = new SpecialistPromptComposer(
        new CapabilityRouter(catalog), catalog, new SpecialistProfileRegistry(catalog),
        new VelocityPromptTemplateRegistry(catalog));

    @Test void usesMarketSpecialistAndProfile() {
        var result = composer.compose("Investigar mercado de colegios en Chile");
        assertEquals("market-researcher", result.specialistId());
        assertTrue(result.prompt().contains("Investigar mercado de colegios en Chile"));
        assertTrue(result.prompt().contains("PERFIL DEL ESPECIALISTA"));
        assertFalse(result.reviewers().isEmpty());
        assertTrue(result.routed());
    }
    @Test void fallsBackToPlanner() {
        var result = composer.compose("Una solicitud sin palabras de especialidad");
        assertEquals("planner", result.specialistId());
        assertFalse(result.routed());
    }
    @Test void rejectsInvalidObjective() {
        assertThrows(IllegalArgumentException.class, () -> composer.compose(" "));
        assertThrows(IllegalArgumentException.class, () -> composer.compose("x".repeat(4001)));
    }
    @Test void doesNotInterpretUserVelocityExpressions() {
        var result = composer.compose("Evaluar mercado: $secret y #set($foo=1)");
        assertTrue(result.prompt().contains("$secret"));
        assertTrue(result.prompt().contains("#set($foo=1)"));
    }
}
