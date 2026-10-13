package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionHandoffStoreTest {
    @TempDir Path dir;
    @Test void importsIdempotentlyAndReleasesDependenciesAfterEvidence() throws Exception {
        var plan=ExecutionGraphTest.plan(ExecutionGraphTest.task("b","a"),ExecutionGraphTest.task("a"));
        var file=dir.resolve("execution.sqlite");
        String token;
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            assertTrue(store.importPlan(plan));
            assertFalse(store.importPlan(plan));
            assertEquals(2,store.list("wf-execution").size());
            var first=store.claim("wf-execution",3,60).orElseThrow();
            assertEquals("a",first.taskId());
            token=first.leaseToken();
            assertTrue(store.claim("wf-execution",3,60).isEmpty());
            assertFalse(store.complete("wf-execution","a","wrong","artifact://test/a"));
            assertThrows(IllegalArgumentException.class,()->store.complete("wf-execution","a",token,""));
        }
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            assertTrue(store.complete("wf-execution","a",token,"artifact://test/a"));
            assertFalse(store.complete("wf-execution","a",token,"artifact://test/a"));
            assertEquals("b",store.claim("wf-execution",3,60).orElseThrow().taskId());
        }
    }
    @Test void rejectsConflictingPlanAndCycles() throws Exception {
        try(var store=new ExecutionHandoffStore(dir.resolve("conflict.sqlite"),new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            assertThrows(IllegalStateException.class,()->store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("b"))));
            assertThrows(IllegalArgumentException.class,()->store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a","b"),ExecutionGraphTest.task("b","a"))));
        }
    }
}
