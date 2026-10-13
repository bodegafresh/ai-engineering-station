package dev.aies.control.methodology;

import dev.aies.control.planning.PlanningContracts;
import java.util.*;

/** Deterministic, fail-closed methodology selection; never executes tools or mutates projects. */
public final class MethodologyRouter {
    private MethodologyRouter() {}
    public enum Methodology {
        SOFTWARE_ENGINEERING, SCIENTIFIC_RESEARCH, BUSINESS_VALIDATION,
        KNOWLEDGE_RESEARCH, INCIDENT_ANALYSIS, DOCUMENTATION, GENERAL
    }
    public record Decision(Methodology methodology, String reason, boolean needsClarification) {}

    public static Decision select(PlanningContracts.Intake raw) {
        var in = PlanningContracts.validate(raw);
        String domain = in.domain().toLowerCase(Locale.ROOT);
        String objective = in.objective().toLowerCase(Locale.ROOT);
        Set<String> capabilities = new HashSet<>();
        for (var requirement : in.requirements()) capabilities.add(requirement.capability().toLowerCase(Locale.ROOT));
        // Explicit domain takes precedence over incidental keywords in the objective.
        Methodology chosen = switch (domain) {
            case "software", "engineering", "implementation" -> Methodology.SOFTWARE_ENGINEERING;
            case "science", "scientific", "experiments", "statistics" -> Methodology.SCIENTIFIC_RESEARCH;
            case "business", "market", "commercial" -> Methodology.BUSINESS_VALIDATION;
            case "research", "knowledge" -> Methodology.KNOWLEDGE_RESEARCH;
            case "incident", "operations", "reliability" -> Methodology.INCIDENT_ANALYSIS;
            case "documentation", "docs" -> Methodology.DOCUMENTATION;
            default -> Methodology.GENERAL;
        };
        if (chosen == Methodology.GENERAL) {
            if (capabilities.contains("software") || capabilities.contains("implementation")) chosen = Methodology.SOFTWARE_ENGINEERING;
            else if (capabilities.contains("experiments") || capabilities.contains("statistics")) chosen = Methodology.SCIENTIFIC_RESEARCH;
            else if (capabilities.contains("research")) chosen = Methodology.KNOWLEDGE_RESEARCH;
        }
        boolean unclear = chosen == Methodology.GENERAL;
        String reason = unclear ? "No supported domain or capability maps to a methodology; operator clarification required"
            : "Selected from explicit domain or declared requirement capabilities; objective is not treated as evidence";
        return new Decision(chosen, reason, unclear);
    }
}
