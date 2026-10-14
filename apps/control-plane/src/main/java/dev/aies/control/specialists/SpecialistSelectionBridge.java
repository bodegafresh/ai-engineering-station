package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** H02-D2B: trusted, explicitly configured bridge. Never grants execution privileges. */
public final class SpecialistSelectionBridge {
    private final SpecialistSelectionRuntime runtime;

    public SpecialistSelectionBridge(String trustedFlag, Path database, ObjectMapper mapper) {
        runtime = SpecialistSelectionRuntime.fromConfiguration(trustedFlag, database, mapper);
    }

    public boolean governedEnabled() { return runtime.governedEnabled(); }

    public JsonObject select(JsonObject body) throws Exception {
        Objects.requireNonNull(body, "request");
        Set<String> capabilities = strings(body.getJsonArray("capabilities"));
        Set<String> tools = strings(body.getJsonArray("permittedTools", new JsonArray()));
        Integer budget = body.getInteger("budget", 0);
        var request = new SpecialistSelectionContract.Request(capabilities, tools, budget);
        var decision = runtime.select(request);
        if (decision.outcome() != SpecialistSelectionContract.Outcome.SELECTED)
            return new JsonObject().put("selected", false);
        // Frozen v1 wire contract: do not expose profile/version or implicit permissions.
        return new JsonObject().put("selected", true)
            .put("specialistId", decision.specialistId())
            .put("costUnits", decision.costUnits());
    }

    private static Set<String> strings(JsonArray array) {
        if (array == null || array.size() > 32) throw new IllegalArgumentException("Invalid list");
        Set<String> values = new HashSet<>();
        for (Object value : array) {
            if (!(value instanceof String id)) throw new IllegalArgumentException("Invalid identifier");
            dev.aies.control.communication.CommunicationContracts.id(id);
            values.add(id);
        }
        return Set.copyOf(values);
    }
}
