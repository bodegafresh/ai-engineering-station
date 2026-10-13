package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRecoverySecurityTest {
    @TempDir Path dir;
    private static final String SHA = "a".repeat(64);

    @Test void rejectsBusRequestsWithoutDurableGrantAndCurrentLease() throws Exception {
        try(var store=new ExecutionHandoffStore(dir.resolve("handoff.sqlite"),new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            assertThrows(SecurityException.class,()->store.requireActiveDocumentation("wf-execution","a","unleased","src/A.java",SHA));
            var grant=new ExecutionCapabilityAuthorization("wf-execution","a","workspace-documentation","src/A.java",SHA);
            assertTrue(store.authorizeCapability(grant));
            assertThrows(SecurityException.class,()->store.requireActiveDocumentation("wf-execution","a","unleased","src/A.java",SHA));
            var work=store.claim("wf-execution",3,60).orElseThrow();
            assertEquals(grant,store.requireActiveDocumentation("wf-execution","a",work.leaseToken(),"src/A.java",SHA));
            assertThrows(SecurityException.class,()->store.requireActiveDocumentation("wf-execution","a",work.leaseToken(),"src/B.java",SHA));
            assertThrows(SecurityException.class,()->store.requireActiveDocumentation("wf-execution","a","stale-token","src/A.java",SHA));
            assertTrue(store.fail("wf-execution","a",work.leaseToken(),true,3));
            assertThrows(SecurityException.class,()->store.requireActiveDocumentation("wf-execution","a",work.leaseToken(),"src/A.java",SHA));
        }
    }

    @Test void journalSurvivesRestartAndRequiresCurrentLease() throws Exception {
        var file=dir.resolve("handoff.sqlite");
        String attempt;
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            store.authorizeCapability(new ExecutionCapabilityAuthorization("wf-execution","a","workspace-documentation","src/A.java",SHA));
            attempt=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            store.recordEffect("wf-execution","a",attempt,"PREPARED",null,null,null);
            store.recordEffect("wf-execution","a",attempt,"FILE_WRITTEN",null,".aies/generated-docs/a.md",SHA);
        }
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            var effect=store.effect("wf-execution","a",attempt).orElseThrow();
            assertEquals("FILE_WRITTEN",effect.phase());
            assertEquals(SHA,effect.documentSha256());
            assertThrows(IllegalArgumentException.class,()->store.recordEffect("wf-execution","a",attempt,"COMPLETED",null,null,null));
            assertTrue(store.fail("wf-execution","a",attempt,true,3));
            assertThrows(SecurityException.class,()->store.recordEffect("wf-execution","a",attempt,"ARTIFACT_RECORDED","artifact-a","x",SHA));
        }
    }
}
