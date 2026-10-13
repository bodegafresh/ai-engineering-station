package dev.aies.control.planning;

import dev.aies.control.communication.CommunicationContracts;
import java.util.*;

/** Immutable provenance for planning artifacts; no inferred or fabricated sources. */
public final class PlanningArtifactEvidence {
    private PlanningArtifactEvidence() {}

    public static List<String> sourceRefs(PlanningContracts.Intake intake, PlanningContracts.Plan plan) {
        PlanningContracts.validate(intake);
        Objects.requireNonNull(plan);
        if (!intake.workflowId().equals(plan.workflowId()))
            throw new IllegalArgumentException("Workflow mismatch");
        var byId = new LinkedHashMap<String, PlanningContracts.Source>();
        for (var source : intake.sources()) byId.put(source.id(), source);
        var required = new LinkedHashSet<String>();
        for (var requirement : plan.requirements()) {
            var original = intake.requirements().stream()
                .filter(r -> r.id().equals(requirement.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown requirement"));
            if (!original.equals(requirement)) throw new IllegalArgumentException("Requirement mutated");
            required.add(requirement.sourceId());
        }
        if (plan.requirements().size() != intake.requirements().size()
            || plan.tasks().size() != plan.requirements().size())
            throw new IllegalArgumentException("Incomplete plan");
        for (var task : plan.tasks()) {
            var requirement = plan.requirements().stream()
                .filter(r -> r.id().equals(task.requirementId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Orphan task"));
            if (!task.id().equals("task-" + requirement.id())
                || !task.capability().equals(requirement.capability())
                || !task.given().equals(requirement.given())
                || !task.when().equals(requirement.when())
                || !task.then().equals(requirement.then())
                || !task.verification().equals(requirement.verification()))
                throw new IllegalArgumentException("Task acceptance traceability mismatch");
        }
        var refs = new ArrayList<String>();
        for (var source : intake.sources()) if (required.contains(source.id()))
            refs.add(source.uri());
        if (refs.size() != required.size()) throw new IllegalArgumentException("Missing provenance");
        return List.copyOf(refs);
    }

    public static CommunicationContracts.Artifact artifact(PlanningContracts.Intake intake,
            PlanningContracts.Plan plan, String content) {
        return CommunicationContracts.validate(new CommunicationContracts.Artifact("1.0",
            plan.workflowId(), "planning-contract", "planning-controller", "planning-plan",
            "text/markdown", content, sourceRefs(intake, plan)));
    }
}
