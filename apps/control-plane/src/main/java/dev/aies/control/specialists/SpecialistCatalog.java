package dev.aies.control.specialists;

import java.util.*;

/** P0.9: capability-based routing metadata; no permission or tool execution is granted here. */
public final class SpecialistCatalog {
    private SpecialistCatalog() {}
    public record Specialist(String id, Set<String> capabilities, Set<String> tools,
                             int maxConcurrent, int costUnits) {
        public Specialist {
            dev.aies.control.communication.CommunicationContracts.id(id);
            if (capabilities == null || capabilities.isEmpty() || capabilities.size() > 32 || tools == null || tools.size() > 32)
                throw new IllegalArgumentException("Invalid specialist capabilities/tools");
            for (String s : capabilities) dev.aies.control.communication.CommunicationContracts.id(s);
            for (String s : tools) dev.aies.control.communication.CommunicationContracts.id(s);
            if (maxConcurrent < 1 || maxConcurrent > 64 || costUnits < 0 || costUnits > 100000)
                throw new IllegalArgumentException("Invalid specialist capacity/cost");
            capabilities = Set.copyOf(capabilities);
            tools = Set.copyOf(tools);
        }
    }
    public record Selection(String specialistId, int costUnits) {}
    public static Optional<Selection> select(Collection<Specialist> available, Set<String> requiredCapabilities,
                                             Set<String> permittedTools, int budget) {
        Objects.requireNonNull(available); Objects.requireNonNull(requiredCapabilities); Objects.requireNonNull(permittedTools);
        if (requiredCapabilities.isEmpty() || budget < 0) throw new IllegalArgumentException("Invalid request");
        return available.stream().filter(Objects::nonNull)
            .filter(s -> s.capabilities().containsAll(requiredCapabilities))
            .filter(s -> permittedTools.containsAll(s.tools()))
            .filter(s -> s.costUnits() <= budget)
            .sorted(Comparator.comparingInt(Specialist::costUnits).thenComparing(Specialist::id))
            .findFirst().map(s -> new Selection(s.id(), s.costUnits()));
    }
}
