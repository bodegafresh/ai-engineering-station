package dev.aies.control.science;

import java.util.List;
import java.util.Objects;

/** P0.7: falsifiable, versioned scientific work plans. */
public final class ScientificContracts {
    private ScientificContracts() {}
    public static final String VERSION = "1.0";
    public record Hypothesis(String id, String claim, String nullHypothesis, String observable,
                             String falsificationRule, String baseline, String minimumSample,
                             List<String> assumptions) {}
    public record Experiment(String id, String hypothesisId, String method, String primaryMetric,
                             String decisionRule, String budgetLimit, List<String> controls) {}
    public record Plan(String schemaVersion, String workflowId, String objective, String domain,
                       List<Hypothesis> hypotheses, List<Experiment> experiments,
                       List<String> evidenceRefs) {}
    private static String required(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max) throw new IllegalArgumentException("Invalid required field");
        return s.strip();
    }
    private static String id(String s) {
        if (s == null || !s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}"))
            throw new IllegalArgumentException("Invalid identifier");
        return s;
    }
    private static List<String> strings(List<String> values, int max) {
        if (values == null || values.size() > max) throw new IllegalArgumentException("Invalid list");
        values.forEach(v -> required(v, 4096));
        return List.copyOf(values);
    }
    public static Plan validate(Plan p) {
        Objects.requireNonNull(p);
        if (!VERSION.equals(p.schemaVersion())) throw new IllegalArgumentException("Invalid schema version");
        id(p.workflowId());
        String objective = required(p.objective(), 12000);
        String domain = id(p.domain());
        if (p.hypotheses() == null || p.hypotheses().isEmpty() || p.hypotheses().size() > 20)
            throw new IllegalArgumentException("1..20 hypotheses required");
        if (p.experiments() == null || p.experiments().isEmpty() || p.experiments().size() > 40)
            throw new IllegalArgumentException("1..40 experiments required");
        var ids = new java.util.HashSet<String>();
        var hypotheses = p.hypotheses().stream().map(h -> {
            Objects.requireNonNull(h);
            if (!ids.add(id(h.id()))) throw new IllegalArgumentException("Duplicate hypothesis");
            return new Hypothesis(h.id(), required(h.claim(), 4096), required(h.nullHypothesis(), 4096),
                required(h.observable(), 4096), required(h.falsificationRule(), 4096),
                required(h.baseline(), 4096), required(h.minimumSample(), 4096), strings(h.assumptions(), 20));
        }).toList();
        var experimentIds = new java.util.HashSet<String>();
        var experiments = p.experiments().stream().map(e -> {
            Objects.requireNonNull(e);
            if (!experimentIds.add(id(e.id()))) throw new IllegalArgumentException("Duplicate experiment");
            if (!ids.contains(e.hypothesisId())) throw new IllegalArgumentException("Unknown hypothesis");
            return new Experiment(e.id(), e.hypothesisId(), required(e.method(), 4096),
                required(e.primaryMetric(), 4096), required(e.decisionRule(), 4096),
                required(e.budgetLimit(), 4096), strings(e.controls(), 20));
        }).toList();
        return new Plan(VERSION, p.workflowId(), objective, domain, hypotheses, experiments,
                        strings(p.evidenceRefs(), 64));
    }
}
