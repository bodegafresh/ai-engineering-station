package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent JVM processes share only SQLite files, never Java objects. */
class GovernedPolicyMultiJvmIntegrationTest {
    @TempDir Path dir;
    private static final String WF="wf-execution", TASK="a";
    private static final GovernedToolResourcePolicy.Grant GRANT =
        new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:software-engineer");

    private Process start(String action,Path handoff,Path policies,String token,Path barrier) throws Exception {
        String cp=System.getProperty("aies.test.runtime.classpath");
        assertNotNull(cp,"Gradle must supply the test runtime classpath for child JVMs");
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        return new ProcessBuilder(java,"-cp",cp,GovernedPolicyMultiJvmChild.class.getName(),
            action,handoff.toString(),policies.toString(),token,barrier.toString())
            .redirectErrorStream(true).start();
    }
    private String result(Process process) throws Exception {
        if (!process.waitFor(20,TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5,TimeUnit.SECONDS);
            fail("Child JVM exceeded 20 seconds");
        }
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.exitValue(),"Child JVM failed: "+output);
        return output;
    }
    private static void ready(Path dir,String file) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!Files.exists(dir.resolve(file))) {
            if(System.nanoTime()>deadline) fail("Child JVM did not reach barrier: "+file);
            Thread.sleep(10);
        }
    }
    @Test void revocationFromAnotherJvmIsImmediatelyObservedAndDurable() throws Exception {
        Path h=dir.resolve("handoff.sqlite"),p=dir.resolve("policies.sqlite");
        String token;
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
            token=handoff.claim(WF,3,600).orElseThrow().leaseToken();
            var attempt=new ExecutionHandoffStore.GovernedAttempt(WF,TASK,token,
                "software-engineer","1.0","software","a".repeat(64),7);
            handoff.bindGovernedAttempt(attempt);
            policies.approve(new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
                "software-engineer","1.0",42,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(GRANT)));
            handoff.bindGovernedPolicyRevision(attempt,42);
        }
        result(start("authorize",h,p,token,dir));
        result(start("revoke",h,p,token,dir));
        result(start("deny",h,p,token,dir));
        try(var policies=new GovernedToolResourcePolicyStore(p)) {
            var events=policies.auditTrail(WF,TASK,token);
            assertTrue(events.stream().anyMatch(e->"ALLOW".equals(e.decision())));
            assertTrue(events.stream().anyMatch(e->"REVOKED".equals(e.decision())));
            assertTrue(events.stream().anyMatch(e->"DENY".equals(e.decision())));
        }
    }
    @Test void conflictingApprovalsAcrossTwoJvmProcessesHaveOneWinner() throws Exception {
        Path h=dir.resolve("unused-handoff.sqlite"),p=dir.resolve("policies.sqlite");
        // Complete DDL/WAL setup before starting the approval race.
        try(var ignored=new GovernedToolResourcePolicyStore(p)) { }
        String token="independent-attempt";
        Process a=start("compete42",h,p,token,dir);
        Process b=start("compete43",h,p,token,dir);
        try {
            ready(dir,"compete42.ready");
            ready(dir,"compete43.ready");
            Files.writeString(dir.resolve("go"),"go");
            String ra=result(a),rb=result(b);
            assertTrue((ra.contains("APPROVED")&&rb.contains("DENIED")) ||
                (ra.contains("DENIED")&&rb.contains("APPROVED")),
                "Expected exactly one approval; a="+ra+" b="+rb);
        } finally {
            if(a.isAlive()) a.destroyForcibly();
            if(b.isAlive()) b.destroyForcibly();
        }
        try(var policies=new GovernedToolResourcePolicyStore(p)) {
            assertEquals(1,policies.auditTrail(WF,TASK,token).stream()
                .filter(e->"APPROVED".equals(e.decision())).count());
        }
    }
}
