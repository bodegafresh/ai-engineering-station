package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Objects;

/** H02-D2: explicit runtime composition boundary. No CDI auto-discovery or Event Bus replacement. */
public final class SpecialistSelectionRuntime {
    public static final String ENABLED_PROPERTY = "aies.specialists.governed-selection.enabled";
    private final SpecialistSelectionFacade facade;
    private final boolean governed;

    private SpecialistSelectionRuntime(SpecialistSelectionFacade facade, boolean governed) {
        this.facade = facade;
        this.governed = governed;
    }

    /** Caller must pass trusted operator configuration, never request/model-supplied flags. */
    public static SpecialistSelectionRuntime configure(boolean enabled, Path database, ObjectMapper mapper) {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(mapper, "mapper");
        GovernedSpecialistSelector adapter = new GovernedSpecialistSelector(database, mapper, enabled);
        SpecialistSelectionFacade facade = enabled
            ? new SpecialistSelectionFacade(SpecialistSelectionFacade.Mode.GOVERNED_V2, adapter::select)
            : SpecialistSelectionFacade.legacy(adapter::select);
        return new SpecialistSelectionRuntime(facade, enabled);
    }

    /** Strict opt-in parsing; malformed values cannot accidentally enable governed selection. */
    public static SpecialistSelectionRuntime fromConfiguration(String rawFlag, Path database, ObjectMapper mapper) {
        if (rawFlag == null || rawFlag.isBlank() || "false".equals(rawFlag))
            return configure(false, database, mapper);
        if ("true".equals(rawFlag)) return configure(true, database, mapper);
        throw new IllegalArgumentException("Invalid governed-selection configuration");
    }

    public boolean governedEnabled() { return governed; }

    /** No fallback after a governed failure. A decision is not execution authorization. */
    public SpecialistSelectionContract.Decision select(SpecialistSelectionContract.Request request) throws Exception {
        return facade.select(Objects.requireNonNull(request));
    }
}
