package dev.aies.control.planning;

import java.text.Normalizer;
import java.util.*;

/** P1.2 deterministic requirements quality gate. No LLM, external fetch or fabricated provenance. */
public final class RequirementsAssessment {
    private RequirementsAssessment() {}
    public record Finding(String code, String requirementId, String detail) {}
    public record Result(List<Finding> findings, int sourceCount, int requirementCount,
                         double traceability, double verifiability) {
        public boolean approved() { return findings.isEmpty(); }
    }
    public static Result assess(PlanningContracts.Intake raw) {
        var intake = PlanningContracts.validate(raw);
        var findings = new ArrayList<Finding>();
        Map<String,String> exact = new HashMap<>();
        Map<String,String> affirmative = new HashMap<>();
        Map<String,String> negative = new HashMap<>();
        for (var r : intake.requirements()) {
            String normalized = normalize(r.statement());
            String duplicateKey = normalized
                + "|verification=" + r.verification()
                + "|given=" + normalize(r.given())
                + "|when=" + normalize(r.when())
                + "|then=" + normalize(r.then())
                + "|dependencies=" + r.dependsOn().stream()
                    .sorted().toList();
            String previous = exact.putIfAbsent(duplicateKey, r.id());
            if (previous != null) findings.add(new Finding("DUPLICATE_REQUIREMENT",r.id(),"Duplicates "+previous));
            String key = normalized.replace("must not ","must ").replace("shall not ","shall ");
            if (normalized.contains("must not ") || normalized.contains("shall not ")) negative.putIfAbsent(key,r.id());
            else affirmative.putIfAbsent(key,r.id());
        }
        for (var entry : affirmative.entrySet()) {
            String other = negative.get(entry.getKey());
            if (other != null) findings.add(new Finding("CONTRADICTORY_REQUIREMENTS",entry.getValue(),"Conflicts with "+other));
        }
        // Contract validation already enforces nonempty acceptance criteria and valid source IDs.
        return new Result(List.copyOf(findings),intake.sources().size(),intake.requirements().size(),1.0,1.0);
    }
    private static String normalize(String input) {
        return Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
            .strip().replaceAll("\\s+"," ");
    }
}
