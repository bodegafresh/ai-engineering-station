package dev.aies.control.planning;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningArtifactEvidenceTest {
    private PlanningContracts.Intake intake() {
        var source = new PlanningContracts.Source("src1", "artifact://spec/task", "a".repeat(64), "Specification");
        var requirement = new PlanningContracts.Requirement("r1", "Implement task API", "src1",
            PlanningContracts.Verification.TEST, "valid JSON", "POST /tasks", "HTTP 201 persisted",
            "software", List.of(), false);
        return new PlanningContracts.Intake("1.0", "wf-provenance", "Build task endpoint", "software",
            List.of(source), List.of(requirement), List.of(), List.of(), 10);
    }
    private PlanningContracts.Plan plan(PlanningContracts.Intake in) {
        var r = in.requirements().getFirst();
        return PlanningPolicy.build(in, Map.of("r1", new PlanningContracts.Task("task-r1", "r1",
            "software", List.of(), "software-engineer", 3, r.statement(), r.given(), r.when(),
            r.then(), r.verification())));
    }
    @Test void carriesRealSourceReferencesIntoImmutableArtifact() {
        var in = intake();
        var a = PlanningArtifactEvidence.artifact(in, plan(in), "# Planning\n" + "detail ".repeat(30));
        assertEquals(List.of("artifact://spec/task"), a.sourceRefs());
    }
    @Test void rejectsDifferentWorkflow() {
        var in = intake();
        var p = plan(in);
        var wrong = new PlanningContracts.Plan("wf-other", p.objective(), p.domain(), p.requirements(),
            p.tasks(), p.openQuestions(), p.budgetUnits(), p.estimatedCostUnits(), p.status());
        assertThrows(IllegalArgumentException.class, () -> PlanningArtifactEvidence.sourceRefs(in, wrong));
    }
    @Test void rejectsChangedAcceptanceCriteria() {
        var in = intake(); var p = plan(in); var t = p.tasks().getFirst();
        var changed = new PlanningContracts.Task(t.id(), t.requirementId(), t.capability(), t.dependencies(),
            t.specialistId(), t.specialistCost(), t.objective(), t.given(), t.when(), "unverified", t.verification());
        var wrong = new PlanningContracts.Plan(p.workflowId(), p.objective(), p.domain(), p.requirements(),
            List.of(changed), p.openQuestions(), p.budgetUnits(), p.estimatedCostUnits(), p.status());
        assertThrows(IllegalArgumentException.class, () -> PlanningArtifactEvidence.sourceRefs(in, wrong));
    }
}
