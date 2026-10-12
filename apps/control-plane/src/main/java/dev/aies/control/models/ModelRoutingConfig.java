package dev.aies.control.models;

import java.util.List;
import java.util.Map;

/** Configuration is operator-owned; no user prompt can change provider permissions. */
public record ModelRoutingConfig(
        String defaultModel,
        Map<String, ModelDefinition> models,
        Map<String, List<String>> specialists) {

    public record ModelDefinition(
            String provider,
            String model,
            boolean enabled,
            boolean cloud,
            String endpoint,
            String credentialEnv) {}
}
