package dev.aies.control.specialists;

import java.util.Objects;

/** H02-D1: explicit selection policy boundary; never grants execution or tool permissions.
 * No runtime wiring is installed by this class. Legacy mode remains the default.
 */
public final class SpecialistSelectionFacade {
    public enum Mode { LEGACY_V1, GOVERNED_V2 }

    @FunctionalInterface
    public interface GovernedSelection {
        SpecialistSelectionContract.Decision select(SpecialistSelectionContract.Request request) throws Exception;
    }

    private final Mode mode;
    private final GovernedSelection governed;

    public SpecialistSelectionFacade(Mode mode, GovernedSelection governed) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.governed = Objects.requireNonNull(governed, "governed");
    }

    /** Default is legacy. Explicit v2 mode never silently falls back on failure. */
    public static SpecialistSelectionFacade legacy(GovernedSelection governed) {
        return new SpecialistSelectionFacade(Mode.LEGACY_V1, governed);
    }

    public SpecialistSelectionContract.Decision select(SpecialistSelectionContract.Request request) throws Exception {
        Objects.requireNonNull(request, "request");
        if (mode == Mode.LEGACY_V1) {
            return SpecialistSelectionContract.select(request, SpecialistSelectionContract.legacyV1());
        }
        SpecialistSelectionContract.Decision decision = Objects.requireNonNull(governed.select(request), "governed decision");
        if (decision.outcome() == SpecialistSelectionContract.Outcome.SELECTED
            && decision.source() != SpecialistSelectionContract.Source.GOVERNED_V2) {
            throw new SecurityException("Governed selection returned an untrusted source");
        }
        return decision;
    }
}
