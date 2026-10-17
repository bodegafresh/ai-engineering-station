package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedPolicyRecoveryIntegrationTest {
    @TempDir Path dir;
    static final String WF="wf-execution", TASK="a";
    static final long REV=42;
    static final GovernedToolResourcePolicy.Grant GRANT =
        new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:software-engineer");
    static ExecutionHandoffStore.GovernedAttempt attempt(String token) {
        return new ExecutionHandoffStore.GovernedAttempt(WF,TASK,token,
            "software-engineer","1.0","software","a".repeat(64),7);
    }
    static GovernedToolResourcePolicy.Snapshot snapshot(String token,long rev) {
        return new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
            "software-engineer","1.0",rev,Instant.parse("2099-01-01T00:00:00Z"),
            false,List.of(GRANT));
    }
    static GovernedToolResourcePolicy.Request request(String token) {
        return new GovernedToolResourcePolicy.Request(WF,TASK,token,
            "software-engineer","1.0",GRANT.tool(),GRANT.action(),GRANT.resource());
    }
    @Test void interruptedApprovalIsNonExecutableUntilExactReplayAndBinding() throws Exception {
        Path h=dir.resolve("handoff.sqlite"),p=dir.resolve("policies.sqlite");
        String token;
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task(TASK)));
            token=handoff.claim(WF,3,600).orElseThrow().leaseToken();
            handoff.bindGovernedAttempt(attempt(token));
            policies.approve(snapshot(token,REV));
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
        }
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
            policies.approve(snapshot(token,REV));
            handoff.bindGovernedPolicyRevision(attempt(token),REV);
            assertDoesNotThrow(()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
            assertThrows(SecurityException.class,
                ()->handoff.bindGovernedPolicyRevision(attempt(token),REV+1));
            assertThrows(SecurityException.class,()->policies.approve(snapshot(token,REV+1)));
            policies.revoke(WF,TASK,token);
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
        }
        try(var handoff=new ExecutionHandoffStore(h,new ObjectMapper());
            var policies=new GovernedToolResourcePolicyStore(p)) {
            assertThrows(SecurityException.class,()->policies.requireAuthorizedCurrent(
                handoff,attempt(token),request(token)));
            var events=policies.auditTrail(WF,TASK,token);
            assertTrue(events.stream().anyMatch(e->"HANDOFF_REJECTED".equals(e.reason())));
            assertTrue(events.stream().anyMatch(e->"ALLOW".equals(e.decision())));
            assertTrue(events.stream().anyMatch(e->"REVOKED".equals(e.decision())));
            assertTrue(events.stream().anyMatch(e->"DENY".equals(e.decision())));
        }
    }
}
