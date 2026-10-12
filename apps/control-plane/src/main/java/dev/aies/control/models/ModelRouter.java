package dev.aies.control.models;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic, permission-aware model selection; no silent cloud opt-in. */
public final class ModelRouter {
    private static final Pattern SAFE_ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private final ModelRoutingConfig config;
    private final ModelAvailability availability;
    private final boolean cloudOptIn;
    private final Map<String, String> environment;

    public ModelRouter(ModelRoutingConfig config, ModelAvailability availability,
                       boolean cloudOptIn, Map<String, String> environment) {
        this.config = Objects.requireNonNull(config);
        this.availability = Objects.requireNonNull(availability);
        this.cloudOptIn = cloudOptIn;
        this.environment = Map.copyOf(Objects.requireNonNull(environment));
        validate();
    }

    private void validate() {
        if (config.models() == null || config.models().isEmpty()
                || config.specialists() == null || config.defaultModel() == null
                || !config.models().containsKey(config.defaultModel())) {
            throw new IllegalArgumentException("Invalid model routing configuration");
        }
        for (var entry : config.models().entrySet()) {
            if (!SAFE_ID.matcher(entry.getKey()).matches() || entry.getValue() == null)
                throw new IllegalArgumentException("Invalid model ID: " + entry.getKey());
            var m = entry.getValue();
            if (blank(m.provider()) || blank(m.model()))
                throw new IllegalArgumentException("Missing provider/model: " + entry.getKey());
            if (!Set.of("ollama", "openai-compatible").contains(m.provider()))
                throw new IllegalArgumentException("Unsupported provider: " + m.provider());
            if (m.cloud() && blank(m.credentialEnv()))
                throw new IllegalArgumentException("Cloud model requires credentialEnv: " + entry.getKey());
        }
        for (var entry : config.specialists().entrySet()) {
            if (!SAFE_ID.matcher(entry.getKey()).matches() || entry.getValue() == null)
                throw new IllegalArgumentException("Invalid specialist ID");
            for (String candidate : entry.getValue())
                if (!config.models().containsKey(candidate))
                    throw new IllegalArgumentException("Unknown model candidate: " + candidate);
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    public ModelSelection select(String specialistId) {
        if (specialistId == null || !SAFE_ID.matcher(specialistId).matches())
            throw new IllegalArgumentException("Invalid specialist ID");
        List<String> candidates = new ArrayList<>(
                config.specialists().getOrDefault(specialistId, List.of()));
        if (!candidates.contains(config.defaultModel())) candidates.add(config.defaultModel());
        List<String> rejected = new ArrayList<>();
        for (String id : candidates) {
            var m = config.models().get(id);
            if (!m.enabled()) { rejected.add(id + ":disabled"); continue; }
            if (m.cloud() && !cloudOptIn) { rejected.add(id + ":cloud-not-authorized"); continue; }
            if (m.cloud() && blank(environment.get(m.credentialEnv()))) {
                rejected.add(id + ":missing-credential"); continue;
            }
            if (!availability.isAvailable(id, m)) { rejected.add(id + ":unavailable"); continue; }
            return new ModelSelection(specialistId, id, m.provider(), m.model(),
                    m.endpoint(), id.equals(config.defaultModel()) && !candidates.get(0).equals(id), rejected);
        }
        throw new NoAvailableModelException(specialistId, rejected);
    }

    public static final class NoAvailableModelException extends IllegalStateException {
        private final List<String> rejected;
        public NoAvailableModelException(String specialist, List<String> rejected) {
            super("No permitted available model for specialist: " + specialist);
            this.rejected = List.copyOf(rejected);
        }
        public List<String> rejectedCandidates() { return rejected; }
    }
}
