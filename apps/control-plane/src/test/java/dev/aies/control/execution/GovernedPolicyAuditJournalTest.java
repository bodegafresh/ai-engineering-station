package dev.aies.control.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedPolicyAuditJournalTest {
    @TempDir Path temp;
    @Test void approvalDecisionsRevocationAndReplaySurviveRestart() throws Exception {
        Path file=temp.resolve("policies.sqlite");
        var grant=new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:engineer");
        var snapshot=new GovernedToolResourcePolicy.Snapshot("wf","task","attempt",
            "engineer","1.0",12,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(grant));
        var request=new GovernedToolResourcePolicy.Request("wf","task","attempt",
            "engineer","1.0","ollama","invoke-model","ollama:engineer");
        try(var store=new GovernedToolResourcePolicyStore(file)) {
            store.approve(snapshot);
            store.approve(snapshot);
            assertTrue(store.evaluate(request,12,Instant.now()).allowed());
            assertFalse(store.evaluate(request,13,Instant.now()).allowed());
            store.revoke("wf","task","attempt");
            assertFalse(store.evaluate(request,12,Instant.now()).allowed());
        }
        try(var store=new GovernedToolResourcePolicyStore(file)) {
            var events=store.auditTrail("wf","task","attempt");
            assertEquals(List.of("APPROVED","REPLAY","ALLOW","DENY","REVOKED","DENY"),
                events.stream().map(GovernedToolResourcePolicyStore.AuditEntry::decision).toList());
            assertEquals("REVISION_MISMATCH",events.get(3).reason());
            assertEquals("REVOKED",events.get(5).reason());
            assertTrue(events.stream().allMatch(e->e.eventId()>0));
        }
    }
    @Test void missingPolicyDenialIsAudited() throws Exception {
        try(var store=new GovernedToolResourcePolicyStore(temp.resolve("empty.sqlite"))) {
            var request=new GovernedToolResourcePolicy.Request("wf","task","attempt",
                "engineer","1.0","ollama","invoke-model","ollama:engineer");
            assertFalse(store.evaluate(request,1,Instant.now()).allowed());
            assertEquals("DENY",store.auditTrail("wf","task","attempt").get(0).decision());
        }
    }
}
