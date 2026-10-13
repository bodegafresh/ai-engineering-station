package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionFailureStoreTest {
    @TempDir Path dir;
    @Test void retryableFailureReturnsToQueueAndExhaustionDeadLetters() throws Exception {
        var plan=ExecutionGraphTest.plan(ExecutionGraphTest.task("a"));
        try(var store=new ExecutionHandoffStore(dir.resolve("retry.sqlite"),new ObjectMapper())) {
            store.importPlan(plan);
            var first=store.claim("wf-execution",2,60).orElseThrow();
            assertFalse(store.fail("wf-execution","a","wrong",true,2));
            assertTrue(store.fail("wf-execution","a",first.leaseToken(),true,2));
            assertFalse(store.fail("wf-execution","a",first.leaseToken(),true,2));
            assertEquals("PENDING",store.list("wf-execution").getFirst().status());
            var second=store.claim("wf-execution",2,60).orElseThrow();
            assertTrue(store.fail("wf-execution","a",second.leaseToken(),true,2));
            assertEquals("DEAD",store.list("wf-execution").getFirst().status());
            assertTrue(store.claim("wf-execution",2,60).isEmpty());
        }
    }
    @Test void permanentFailureDoesNotRetry() throws Exception {
        try(var store=new ExecutionHandoffStore(dir.resolve("permanent.sqlite"),new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            var lease=store.claim("wf-execution",3,60).orElseThrow();
            assertTrue(store.fail("wf-execution","a",lease.leaseToken(),false,3));
            assertEquals("DEAD",store.list("wf-execution").getFirst().status());
        }
    }
}
