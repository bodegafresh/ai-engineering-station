package dev.aies.control.specialists;

import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistSelectionFacadeTest {
    private static SpecialistSelectionContract.Request request(String capability) {
        return new SpecialistSelectionContract.Request(Set.of(capability), Set.of(), 10);
    }

    @Test void defaultLegacyNeverCallsGovernedAdapter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var facade = SpecialistSelectionFacade.legacy(r -> {
            calls.incrementAndGet();
            throw new SQLException("database must not be opened");
        });
        var result = facade.select(request("research"));
        assertEquals(SpecialistSelectionContract.Outcome.SELECTED, result.outcome());
        assertEquals("researcher", result.specialistId());
        assertEquals(SpecialistSelectionContract.Source.LEGACY_V1, result.source());
        assertEquals(0, calls.get());
    }

    @Test void governedModePropagatesDatabaseFailureWithoutFallback() {
        var facade = new SpecialistSelectionFacade(SpecialistSelectionFacade.Mode.GOVERNED_V2,
            r -> { throw new SQLException("registry unavailable"); });
        assertThrows(SQLException.class, () -> facade.select(request("research")));
    }

    @Test void governedModeRejectsLegacyAssignments() {
        var facade = new SpecialistSelectionFacade(SpecialistSelectionFacade.Mode.GOVERNED_V2,
            r -> SpecialistSelectionContract.select(r, SpecialistSelectionContract.legacyV1()));
        assertThrows(SecurityException.class, () -> facade.select(request("research")));
    }

    @Test void governedModePreservesExplicitRejection() throws Exception {
        var facade = new SpecialistSelectionFacade(SpecialistSelectionFacade.Mode.GOVERNED_V2,
            r -> SpecialistSelectionContract.Decision.rejected(SpecialistSelectionContract.Outcome.BLOCKED_SPECIALIST));
        assertEquals(SpecialistSelectionContract.Outcome.BLOCKED_SPECIALIST,
            facade.select(request("research")).outcome());
    }

    @Test void governedModeAcceptsOnlyGovernedAssignment() throws Exception {
        var facade = new SpecialistSelectionFacade(SpecialistSelectionFacade.Mode.GOVERNED_V2,
            r -> new SpecialistSelectionContract.Decision(SpecialistSelectionContract.Outcome.SELECTED,
                "documentation.specialist", "1.0.0", SpecialistSelectionContract.Source.GOVERNED_V2, 3));
        assertEquals(SpecialistSelectionContract.Source.GOVERNED_V2,
            facade.select(request("documentation.technical.authoring")).source());
    }
}
