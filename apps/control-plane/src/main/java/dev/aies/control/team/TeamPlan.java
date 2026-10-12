package dev.aies.control.team;
import java.util.List;
import java.util.Set;
public record TeamPlan(String objective, List<Step> steps) {
    public record Step(String id, String specialist, String objective, Set<String> dependsOn, Set<String> reviewers) {}
    public TeamPlan { steps = List.copyOf(steps); }
}
