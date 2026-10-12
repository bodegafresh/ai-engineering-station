package dev.aies.control.models;

/** Implementations must be bounded by timeouts and must not execute inference. */
@FunctionalInterface
public interface ModelAvailability {
    boolean isAvailable(String configId, ModelRoutingConfig.ModelDefinition definition);
}
