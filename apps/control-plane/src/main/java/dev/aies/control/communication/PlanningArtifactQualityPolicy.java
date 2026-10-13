package dev.aies.control.communication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Fail-closed structural review of the immutable planning-plan artifact envelope. */
public final class PlanningArtifactQualityPolicy {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private PlanningArtifactQualityPolicy() {}

    public static List<String> findings(CommunicationContracts.Artifact artifact) {
        var issues = new ArrayList<String>();
        if (!"planning-plan".equals(artifact.kind())) return List.of("Not a planning-plan artifact");
        if (!"text/markdown".equals(artifact.contentType())) return List.of("Planning plan must be markdown");
        final String begin = "## Requirements and task assignments";
        final String end = "## State";
        int a = artifact.content().indexOf(begin);
        int b = artifact.content().indexOf(end, Math.max(0, a + begin.length()));
        if (a < 0 || b < 0 || b <= a) return List.of("Missing canonical plan JSON section");
        JsonNode plan;
        try {
            plan = MAPPER.readTree(artifact.content().substring(a + begin.length(), b).trim());
        } catch (Exception e) { return List.of("Invalid canonical plan JSON"); }
        if (plan == null || !plan.isObject()) return List.of("Canonical plan must be an object");
        issues.addAll(dev.aies.control.planning.PlanningProvenance.findings(artifact,plan));
        if (!artifact.workflowId().equals(text(plan, "workflowId"))) issues.add("Workflow correlation mismatch");
        if (!"AUDIT_REQUIRED".equals(text(plan, "status"))) issues.add("Plan is not audit-ready");
        JsonNode reqs = plan.path("requirements"), tasks = plan.path("tasks");
        if (!reqs.isArray() || reqs.isEmpty() || !tasks.isArray() || tasks.size() != reqs.size()) {
            issues.add("Incomplete requirements/tasks"); return List.copyOf(issues);
        }
        var byReq = new HashMap<String, JsonNode>();
        var sourceIds = new HashSet<String>();
        for (JsonNode r : reqs) {
            String id = text(r,"id"), source = text(r,"sourceId");
            if (id.isBlank() || byReq.putIfAbsent(id,r)!=null) issues.add("Duplicate or missing requirement ID");
            if (source.isBlank()) issues.add("Missing requirement source ID");
            else sourceIds.add(source);
            if (text(r,"statement").isBlank() || text(r,"given").isBlank() || text(r,"when").isBlank()
                    || text(r,"then").isBlank() || text(r,"verification").isBlank())
                issues.add("Incomplete requirement acceptance criteria");
        }
        var refs = new HashSet<String>();
        for (String ref : artifact.sourceRefs()) {
            if (!(ref.startsWith("artifact://") || ref.startsWith("https://")) || !refs.add(ref))
                issues.add("Invalid or duplicate source reference");
        }
        if (refs.size() < sourceIds.size()) issues.add("Missing source provenance references");
        // The artifact carries URIs but not source ID -> URI/digest mapping. That cannot be proven here.
        var taskIds = new HashSet<String>();
        long cost = 0;
        for (JsonNode t : tasks) {
            String rid = text(t,"requirementId"), tid = text(t,"id");
            JsonNode r = byReq.get(rid);
            if (r == null || !tid.equals("task-"+rid) || !taskIds.add(tid)) {
                issues.add("Orphan or duplicate task"); continue;
            }
            for (String key : List.of("capability","given","when","then","verification"))
                if (!text(t,key).equals(text(r,key))) issues.add("Task requirement mismatch: "+key);
            if (!text(t,"objective").equals(text(r,"statement"))) issues.add("Task objective mismatch");
            if (text(t,"specialistId").isBlank() || "unassigned".equals(text(t,"specialistId")))
                issues.add("Missing specialist assignment");
            JsonNode c = t.path("specialistCost");
            if (!c.isIntegralNumber() || c.longValue()<0) issues.add("Invalid specialist cost");
            else cost += c.longValue();
            JsonNode deps = t.path("dependencies"), expected = r.path("dependsOn");
            if (!deps.isArray() || !expected.isArray() || deps.size()!=expected.size()) issues.add("Task dependency mismatch");
            else for(int i=0;i<deps.size();i++)
                if (!deps.get(i).isTextual() || !deps.get(i).asText().equals("task-"+expected.get(i).asText()))
                    issues.add("Task dependency mismatch");
        }
        JsonNode budget = plan.path("budgetUnits"), total = plan.path("estimatedCostUnits");
        if (!budget.isIntegralNumber() || budget.longValue()<0 || !total.isIntegralNumber()
                || total.longValue()!=cost || cost>budget.longValue()) issues.add("Budget/cost inconsistency");
        return List.copyOf(issues);
    }
    private static String text(JsonNode node,String key) {
        JsonNode value=node.path(key); return value.isTextual()?value.asText():"";
    }
}
