package dev.aies.control.communication;

import java.util.ArrayList;
import java.util.List;

/** Deterministic structural checks, not a semantic LLM reviewer. */
public final class CommunicationQualityPolicy {
    private CommunicationQualityPolicy() {}
    public static List<String> findings(CommunicationStore.StoredRecord record) {
        List<String> issues = new ArrayList<>();
        if (record.payload() instanceof CommunicationContracts.Message m) {
            if (m.acceptanceCriteria().isEmpty()) issues.add("Missing Given/When/Then acceptance criteria");
            if (m.objective().strip().length() < 30) issues.add("Objective needs more detail");
            if (m.instructions().isEmpty()) issues.add("Missing instructions");
            if (m.expectedOutputs().isEmpty()) issues.add("Missing expected outputs");
        } else if (record.payload() instanceof CommunicationContracts.Artifact a) {
            if ("planning-plan".equals(a.kind())) return PlanningArtifactQualityPolicy.findings(a);
            if (a.content().strip().length() < 100) issues.add("Artifact needs more substantive content");
            if (a.contentType().equals("text/markdown") && !a.content().contains("#"))
                issues.add("Markdown artifact needs a heading");
        } else {
            issues.add("Only messages and artifacts can be audited");
        }
        return List.copyOf(issues);
    }
}
