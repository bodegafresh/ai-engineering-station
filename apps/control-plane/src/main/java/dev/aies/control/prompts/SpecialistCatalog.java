package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Declarative capabilities only; never grants tool or network permissions. */
@ApplicationScoped
public class SpecialistCatalog {
    public record Specialist(String id, String template, List<String> capabilities, List<String> reviewers, int maxIterations) {}
    private final Map<String, Specialist> specialists;

    public SpecialistCatalog(ObjectMapper mapper) {
        try (var input = getClass().getClassLoader().getResourceAsStream("prompts/catalog.json")) {
            if (input == null) throw new IllegalStateException("Missing specialist catalog");
            var tree = mapper.readTree(input);
            var entries = new java.util.LinkedHashMap<String, Specialist>();
            for (var node : tree.path("specialists")) {
                String id = node.path("id").asText();
                String template = node.path("template").asText();
                var caps = new java.util.ArrayList<String>();
                node.path("capabilities").forEach(n -> caps.add(n.asText()));
                var reviewers = new java.util.ArrayList<String>();
                node.path("reviewers").forEach(n -> reviewers.add(n.asText()));
                int iterations = node.path("maxIterations").asInt();
                if (!id.matches("[a-z][a-z0-9-]{1,63}") || !id.equals(template) || iterations < 1 || iterations > 20 || entries.containsKey(id))
                    throw new IllegalArgumentException("Invalid specialist descriptor: " + id);
                entries.put(id, new Specialist(id, template, List.copyOf(caps), List.copyOf(reviewers), iterations));
            }
            specialists = Map.copyOf(entries);
        } catch (IOException e) { throw new IllegalStateException("Cannot load specialist catalog", e); }
    }
    public Set<String> ids() { return specialists.keySet(); }
    public Specialist get(String id) {
        var found = specialists.get(id);
        if (found == null) throw new IllegalArgumentException("Unknown specialist: " + id);
        return found;
    }
    public List<Specialist> matchingCapability(String capability) {
        return specialists.values().stream().filter(s -> s.capabilities().contains(capability)).sorted(java.util.Comparator.comparing(Specialist::id)).collect(Collectors.toList());
    }
}
