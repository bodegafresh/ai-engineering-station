package dev.aies.control.specialists;

import java.util.*;

/** H02-B: inert, immutable selection boundary. Selection never grants execution permissions. */
public final class SpecialistSelectionContract {
    private SpecialistSelectionContract() {}
    public enum Outcome { SELECTED, NO_MATCH, BLOCKED_SPECIALIST, NEEDS_CLARIFICATION }
    public enum Source { LEGACY_V1, GOVERNED_V2, PLANNING_PREVIEW }
    public record Request(Set<String> capabilities, Set<String> permittedTools, int budget) {
        public Request {
            capabilities = checked(capabilities, false);
            permittedTools = checked(permittedTools, true);
            if (budget < 0 || budget > 100000) throw new IllegalArgumentException("Invalid budget");
        }
    }
    public record Candidate(String id, String version, Set<String> capabilities, Set<String> requiredTools,
                            int maxConcurrent, int costUnits, Source source) {
        public Candidate {
            identifier(id);
            Objects.requireNonNull(version);
            if (!version.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))
                throw new IllegalArgumentException("Invalid version");
            capabilities = checked(capabilities, false);
            requiredTools = checked(requiredTools, true);
            if (maxConcurrent < 1 || maxConcurrent > 64 || costUnits < 0 || costUnits > 100000)
                throw new IllegalArgumentException("Invalid capacity/cost");
            Objects.requireNonNull(source);
        }
    }
    public record Decision(Outcome outcome, String specialistId, String version, Source source, int costUnits) {
        public Decision {
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.SELECTED) {
                identifier(specialistId);
                Objects.requireNonNull(version);
                Objects.requireNonNull(source);
                if (costUnits < 0) throw new IllegalArgumentException("Invalid cost");
            } else if (specialistId != null || version != null || source != null || costUnits != 0) {
                throw new IllegalArgumentException("Rejected decision cannot carry an assignment");
            }
        }
        public static Decision rejected(Outcome reason) {
            if (reason == Outcome.SELECTED) throw new IllegalArgumentException("Not a rejection");
            return new Decision(reason, null, null, null, 0);
        }
    }
    /** A pure selector. Caller is responsible for filtering eligibility (e.g. ACTIVE) before passing candidates. */
    public static Decision select(Request request, Collection<Candidate> eligible) {
        Objects.requireNonNull(request); Objects.requireNonNull(eligible);
        return eligible.stream().map(Objects::requireNonNull)
            .filter(c -> c.capabilities().containsAll(request.capabilities()))
            .filter(c -> request.permittedTools().containsAll(c.requiredTools()))
            .filter(c -> c.costUnits() <= request.budget())
            .sorted(Comparator.comparingInt(Candidate::costUnits).thenComparing(Candidate::id).thenComparing(Candidate::version))
            .findFirst().map(c -> new Decision(Outcome.SELECTED,c.id(),c.version(),c.source(),c.costUnits()))
            .orElseGet(() -> Decision.rejected(Outcome.NO_MATCH));
    }
    /** Exact legacy metadata adapter; no database, status, or operator claims. */
    public static List<Candidate> legacyV1() {
        return List.of(
            new Candidate("researcher","1.0.0",Set.of("research"),Set.of(),2,1,Source.LEGACY_V1),
            new Candidate("requirements-auditor","1.0.0",Set.of("requirements","audit"),Set.of(),1,2,Source.LEGACY_V1),
            new Candidate("software-engineer","1.0.0",Set.of("software","implementation"),Set.of(),1,3,Source.LEGACY_V1),
            new Candidate("experiment-analyst","1.0.0",Set.of("statistics","experiments"),Set.of(),1,2,Source.LEGACY_V1));
    }
    private static Set<String> checked(Set<String> values, boolean emptyAllowed) {
        Objects.requireNonNull(values);
        if (values.size() > 32 || (!emptyAllowed && values.isEmpty())) throw new IllegalArgumentException("Invalid capability/tool set");
        values.forEach(SpecialistSelectionContract::identifier);
        return Set.copyOf(values);
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9.-]{1,127}")) throw new IllegalArgumentException("Invalid identifier");
    }
}
