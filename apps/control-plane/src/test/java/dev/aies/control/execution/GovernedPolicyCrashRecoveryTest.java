package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real child-process SIGKILL-equivalent between two independent SQLite stores. */
class GovernedPolicyCrashRecoveryTest {
    @TempDir Path dir;
    private static final String WF="wf-execution", TASK="a";
    private static final GovernedToolResourcePolicy.Grant GRANT =
        new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:software-engineer");

    private static ExecutionHandoffStore.GovernedAttempt attempt(String token) {
        return new ExecutionHandoffStore.GovernedAttempt(WF,TASK,token,
            "software-engineer","1.0","software","a".repeat(64),7);
    }
    private static GovernedToolResourcePolicy.Request request(String token) {
        return new GovernedToolResourcePolicy.Request(WF,TASK,token,
            "software-engineer","1.0",GRANT.tool(),GRANT.action(),GRANT.resource());
    }
    private Process start(String action,Path handoff,Path policy,String token) throws Exception {
        String cp=System.getProperty("aies.test.runtime.classpath");
        assertNotNull(cp,"Gradle must provide child JVM classpath");
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",cp,GovernedPolicyMultiJvmChild.class.getName(),action,
            handoff.toString(),policy.toString(),token,dir.toString())
            .redirectErrorStream(true).start();
    }
    private static void awaitMarker(Path file,Process child) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(file)) {
            if (!child.isAlive()) {
                String output=new String(child.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                fail("Child exited before crash barrier ("+child.exitValue()+"): "+output);
            }
            if (System.nanoTime()>deadline) fail("Timed out waiting for crash barrier");
            Thread.sleep(10);
        }
    }
    @Test void killAfterDurableApprovalNeverAuthorizesUntilExactReplayAndBinding() throws Exception {
        Path h=dir.resolve("handoff.sqlite"),p=dir.resolve("policies.sqlite");
        String token;
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
            token=handoff.claim(WF,3,600).orElseThrow().leaseToken();
            handoff.bindGovernedAttempt(attempt(token));
        }
        Process child=start("approveThenWaitForKill",h,p,token);
        try {
            awaitMarker(dir.resolve("approved.ready"),child);
            assertTrue(child.isAlive(),"Crash must kill a live process");
            child.destroyForcibly();
            assertTrue(child.waitFor(10,TimeUnit.SECONDS),"Child must terminate after forced kill");
            assertNotEquals(0,child.exitValue(),"Crash must be abnormal");
        } finally {
            if(child.isAlive()) { child.destroyForcibly(); child.waitFor(5,TimeUnit.SECONDS); }
        }
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)),"Unbound policy must fail closed after crash");
            var snapshot=new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
                "software-engineer","1.0",42,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(GRANT));
            policies.approve(snapshot); // Exact replay is allowed, never a new grant.
            assertThrows(SecurityException.class,()->policies.approve(
                new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
                    "software-engineer","1.0",43,snapshot.expiresAt(),false,List.of(GRANT))));
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)),"Replay alone must not authorize");
            handoff.bindGovernedPolicyRevision(attempt(token),42);
            assertDoesNotThrow(()->policies.requireAuthorizedCurrent(handoff,attempt(token),request(token)));
            policies.revoke(WF,TASK,token);
        }
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)),"Revocation must survive another restart");
            assertEquals(1,policies.auditTrail(WF,TASK,token).stream()
                .filter(e->"APPROVED".equals(e.decision())).count());
            assertTrue(policies.auditTrail(WF,TASK,token).stream().anyMatch(e->"REPLAY".equals(e.decision())));
            assertTrue(policies.auditTrail(WF,TASK,token).stream().anyMatch(e->"REVOKED".equals(e.decision())));
        }
    }
}
