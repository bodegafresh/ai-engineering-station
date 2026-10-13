package dev.aies.control.execution;

import dev.aies.control.planning.PlanningContracts;
import java.util.*;

/** Validated, deterministic DAG for audited planning contracts. */
public final class ExecutionGraph {
    private ExecutionGraph() {}
    public static List<PlanningContracts.Task> ordered(PlanningContracts.Plan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.tasks() == null || plan.tasks().isEmpty() || plan.tasks().size() > 100)
            throw new IllegalArgumentException("Invalid task count");
        Map<String, PlanningContracts.Task> tasks = new LinkedHashMap<>();
        for (var task : plan.tasks()) {
            if (task == null || task.id() == null || task.dependencies() == null ||
                tasks.putIfAbsent(task.id(), task) != null)
                throw new IllegalArgumentException("Invalid or duplicate task");
            if (task.specialistId() == null || task.specialistId().isBlank() ||
                "unassigned".equals(task.specialistId()) || task.specialistCost() < 0)
                throw new IllegalArgumentException("Task not assigned");
        }
        Map<String,Integer> degree = new HashMap<>();
        Map<String,List<String>> successors = new HashMap<>();
        for (var task : tasks.values()) {
            var unique = new HashSet<String>();
            for (var dep : task.dependencies()) {
                if (!tasks.containsKey(dep) || dep.equals(task.id()) || !unique.add(dep))
                    throw new IllegalArgumentException("Unknown, self or duplicate dependency");
                successors.computeIfAbsent(dep, k -> new ArrayList<>()).add(task.id());
            }
            degree.put(task.id(), unique.size());
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        degree.forEach((id,n) -> { if (n == 0) ready.add(id); });
        List<PlanningContracts.Task> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            var id = ready.remove();
            ordered.add(tasks.get(id));
            for (var next : successors.getOrDefault(id, List.of()))
                if (degree.compute(next, (k,n) -> n - 1) == 0) ready.add(next);
        }
        if (ordered.size() != tasks.size()) throw new IllegalArgumentException("Cyclic DAG");
        if (plan.estimatedCostUnits() > plan.budgetUnits()) throw new IllegalArgumentException("Budget exceeded");
        return List.copyOf(ordered);
    }
}
