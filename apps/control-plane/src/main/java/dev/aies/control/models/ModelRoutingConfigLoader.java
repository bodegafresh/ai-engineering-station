package dev.aies.control.models;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class ModelRoutingConfigLoader {
    private ModelRoutingConfigLoader() {}

    public static ModelRoutingConfig loadClasspath(String resource) {
        if (!"models/routing.json".equals(resource))
            throw new IllegalArgumentException("Only operator-owned model routing config is allowed");
        try (InputStream input = ModelRoutingConfigLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing resource: " + resource);
            return new ObjectMapper().readValue(input, ModelRoutingConfig.class);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load model routing config", e);
        }
    }
}
