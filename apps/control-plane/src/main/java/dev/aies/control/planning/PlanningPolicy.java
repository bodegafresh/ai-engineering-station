package dev.aies.control.planning;

import java.util.*;

/** P0.15-P0.16: deterministic traceability, DAG and acceptance checks. */
public final class PlanningPolicy {
    private PlanningPolicy(){}
    public record Review(boolean approved,List<String> findings,double traceability,double verifiability) {}
    public static Review review(PlanningContracts.Intake raw) {
        var in=PlanningContracts.validate(raw);
        var findings=new ArrayList<String>();
        var methodology=dev.aies.control.methodology.MethodologyRouter.select(in);
        if(methodology.needsClarification()) findings.add("Unknown methodology: clarify domain or capabilities");
        var requirements=RequirementsAssessment.assess(in);
        for(var finding:requirements.findings())
            findings.add(finding.code()+" ["+finding.requirementId()+"] "+finding.detail());
        if(!in.openQuestions().isEmpty())findings.add("Unresolved questions: "+in.openQuestions().size());
        var deps=new HashMap<String,List<String>>();
        for(var r:in.requirements()) {
            deps.put(r.id(),r.dependsOn());
            if(r.critical()&&r.verification()==PlanningContracts.Verification.INSPECTION)
                findings.add("Critical requirement "+r.id()+" needs objective test, analysis or demonstration");
        }
        var states=new HashMap<String,Integer>();
        for(var id:deps.keySet())if(cyclic(id,deps,states)) {
            findings.add("Cyclic dependencies");break;
        }
        return new Review(findings.isEmpty(),List.copyOf(findings),
            requirements.traceability(),requirements.verifiability());
    }
    private static boolean cyclic(String id,Map<String,List<String>> graph,Map<String,Integer> states) {
        int state=states.getOrDefault(id,0);
        if(state==1)return true;
        if(state==2)return false;
        states.put(id,1);
        for(var dep:graph.getOrDefault(id,List.of()))if(cyclic(dep,graph,states))return true;
        states.put(id,2);
        return false;
    }
    public static PlanningContracts.Plan build(PlanningContracts.Intake raw,
                                                Map<String,PlanningContracts.Task> assignments) {
        var in=PlanningContracts.validate(raw);
        var tasks=new ArrayList<PlanningContracts.Task>();
        int cost=0;
        for(var r:in.requirements()) {
            var task=assignments.get(r.id());
            if(task==null)throw new IllegalArgumentException("Missing assignment: "+r.id());
            if(!task.requirementId().equals(r.id())||!task.id().equals("task-"+r.id()))
                throw new IllegalArgumentException("Invalid task traceability");
            if(!task.dependencies().equals(r.dependsOn().stream().map(x->"task-"+x).toList()))
                throw new IllegalArgumentException("Invalid dependencies");
            if(task.specialistCost()<0)throw new IllegalArgumentException("Invalid cost");
            cost=Math.addExact(cost,task.specialistCost());
            tasks.add(task);
        }
        var review=review(in);
        String status=!review.approved()?"NEEDS_CLARIFICATION":
          tasks.stream().anyMatch(t->"unassigned".equals(t.specialistId()))?"BLOCKED_SPECIALIST":
          cost>in.budgetUnits()?"BLOCKED_BUDGET":"AUDIT_REQUIRED";
        return new PlanningContracts.Plan(in.workflowId(),in.objective(),in.domain(),in.requirements(),
            List.copyOf(tasks),in.openQuestions(),in.budgetUnits(),cost,status);
    }
}
