package dev.aies.control.execution;

import dev.aies.control.planning.PlanningContracts;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionGraphTest {
    static PlanningContracts.Task task(String id, String... dependencies) {
        return new PlanningContracts.Task(id,"r-"+id,"software",List.of(dependencies),"software-engineer",1,
            "Implement", "Given", "When", "Then",PlanningContracts.Verification.TEST);
    }
    static PlanningContracts.Plan plan(PlanningContracts.Task... tasks) {
        return new PlanningContracts.Plan("wf-execution","Implement API","software",List.of(),
            List.of(tasks),List.of(),10,tasks.length,"AUDIT_REQUIRED");
    }
    @Test void deterministicTopologicalOrdering() {
        var result=ExecutionGraph.ordered(plan(task("c","b"),task("b","a"),task("a")));
        assertEquals(List.of("a","b","c"),result.stream().map(PlanningContracts.Task::id).toList());
    }
    @Test void rejectsCyclesAndUnknownDependencies() {
        assertThrows(IllegalArgumentException.class,()->ExecutionGraph.ordered(plan(task("a","b"),task("b","a"))));
        assertThrows(IllegalArgumentException.class,()->ExecutionGraph.ordered(plan(task("a","missing"))));
    }
    @Test void rejectsUnassignedAndDuplicateTasks() {
        assertThrows(IllegalArgumentException.class,()->ExecutionGraph.ordered(plan(task("a"),task("a"))));
        var t=task("a");
        var unassigned=new PlanningContracts.Task(t.id(),t.requirementId(),t.capability(),t.dependencies(),"unassigned",0,
            t.objective(),t.given(),t.when(),t.then(),t.verification());
        assertThrows(IllegalArgumentException.class,()->ExecutionGraph.ordered(plan(unassigned)));
    }
}
