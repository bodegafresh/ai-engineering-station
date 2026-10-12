package dev.aies.control.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.prompts.SpecialistCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityRouterTest {
    private final CapabilityRouter router=new CapabilityRouter(new SpecialistCatalog(new ObjectMapper()));
    @Test void commercialProblemSelectsComplementaryExperts() {
        var result=router.preview("Evaluar mercado, geografía, economía, finanzas y rentabilidad de suscripción SaaS en comunas");
        var ids=result.specialists().stream().map(CapabilityRouter.Selection::specialistId).toList();
        assertTrue(ids.contains("market-researcher"), ids.toString());
        assertTrue(ids.contains("growth-strategist"), ids.toString());
        assertTrue(ids.contains("geography-analyst"), ids.toString());
        assertFalse(result.executable());
        assertFalse(result.requiredReviews().isEmpty());
    }
    @Test void avoidsInventedMatchAndPermissions() {
        var result=router.preview("Una solicitud sin palabras de especialidad");
        assertTrue(result.specialists().isEmpty());
        assertFalse(result.executable());
    }
    @Test void validatesInput() {
        assertThrows(IllegalArgumentException.class,()->router.preview(" "));
        assertThrows(IllegalArgumentException.class,()->router.preview("x".repeat(4001)));
    }
    @Test void accentNormalizationWorks() {
        assertTrue(router.preview("Estudiar geografía y política regional").specialists().stream()
          .anyMatch(s->s.specialistId().equals("geography-analyst")));
    }
}
