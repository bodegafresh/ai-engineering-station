package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionSingleFlightConcurrencyTest {
    @TempDir Path dir;

    @Test void twoConnectionsReserveAtMostOneInvocationAndSurviveRestart() throws Exception {
        Path db=dir.resolve("handoff.sqlite");
        String workflow="wf-execution", task="a", source="A.java";
        String sha="a".repeat(64);
        String attempt;
        try(var store=new ExecutionHandoffStore(db,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(task)));
            store.authorizeCapability(new ExecutionCapabilityAuthorization(
                workflow,task,"workspace-documentation",source,sha));
            attempt=store.claim(workflow,3,120).orElseThrow().leaseToken();
            store.recordEffect(workflow,task,attempt,"PREPARED",null,null,null);
        }
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            Callable<Boolean> reserve=()->{
                start.await();
                try(var store=new ExecutionHandoffStore(db,new ObjectMapper())) {
                    return store.reserveModelInvocation(workflow,task,attempt,source,sha);
                }
            };
            Future<Boolean> first=pool.submit(reserve),second=pool.submit(reserve);
            start.countDown();
            assertEquals(1,(first.get(15,TimeUnit.SECONDS)?1:0)+(second.get(15,TimeUnit.SECONDS)?1:0));
        } finally {pool.shutdownNow();}
        try(var restarted=new ExecutionHandoffStore(db,new ObjectMapper())) {
            assertTrue(restarted.modelInvocationReserved(workflow,task,attempt));
            assertEquals("AMBIGUOUS_INVOCATION",restarted.invocationRecoveryStatus(workflow,task,attempt));
            assertFalse(restarted.reserveModelInvocation(workflow,task,attempt,source,sha));
        }
    }
}
