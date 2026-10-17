package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedPolicyEarlyDenialAuditTest {
    @TempDir Path temp;
    private static GovernedToolResourcePolicy.Request request(String id) {
        return new GovernedToolResourcePolicy.Request("wf","task","attempt",id,"1.0",
            "ollama","invoke-model","ollama:engineer");
    }
    @Test void missingPolicyIsAuditedBeforeHandoffAccess() throws Exception {
        Path db=temp.resolve("policy.sqlite");
        var attempt=new ExecutionHandoffStore.GovernedAttempt(
            "wf","task","attempt","engineer","1.0","software");
        try(var store=new GovernedToolResourcePolicyStore(db)) {
            assertThrows(SecurityException.class,
                ()->store.requireAuthorizedCurrent(null,attempt,request("engineer")));
        }
        try(var store=new GovernedToolResourcePolicyStore(db)) {
            var events=store.auditTrail("wf","task","attempt");
            assertEquals(1,events.size());
            assertEquals("PRECHECK",events.get(0).type());
            assertEquals("MISSING_POLICY",events.get(0).reason());
            assertEquals("DENY",events.get(0).decision());
        }
    }
    @Test void identityMismatchIsAuditedWithoutLease() throws Exception {
        Path db=temp.resolve("policy.sqlite");
        var attempt=new ExecutionHandoffStore.GovernedAttempt(
            "wf","task","attempt","engineer","1.0","software");
        try(var handoff=new ExecutionHandoffStore(temp.resolve("handoff.sqlite"),new ObjectMapper());
            var store=new GovernedToolResourcePolicyStore(db)) {
            assertThrows(SecurityException.class,
                ()->store.requireAuthorized(handoff,attempt,request("other"),7));
            var events=store.auditTrail("wf","task","attempt");
            assertEquals("ATTEMPT_IDENTITY_MISMATCH",events.get(0).reason());
        }
    }
    @Test void unboundRevisionIsAuditedAsHandoffRejection() throws Exception {
        Path db=temp.resolve("policy.sqlite");
        var attempt=new ExecutionHandoffStore.GovernedAttempt(
            "wf","task","attempt","engineer","1.0","software");
        try(var handoff=new ExecutionHandoffStore(temp.resolve("handoff.sqlite"),new ObjectMapper());
            var store=new GovernedToolResourcePolicyStore(db)) {
            assertThrows(SecurityException.class,
                ()->store.requireAuthorized(handoff,attempt,request("engineer"),7));
            var events=store.auditTrail("wf","task","attempt");
            assertEquals("HANDOFF_REJECTED",events.get(0).reason());
        }
    }
}
