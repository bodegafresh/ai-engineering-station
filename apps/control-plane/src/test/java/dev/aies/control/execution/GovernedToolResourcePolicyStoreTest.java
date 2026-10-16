package dev.aies.control.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedToolResourcePolicyStoreTest {
    @TempDir Path temp;
    private static final Instant FUTURE = Instant.parse("2099-01-01T00:00:00Z");
    private static final GovernedToolResourcePolicy.Grant GRANT =
        new GovernedToolResourcePolicy.Grant("storage","read","artifact://project/a");
    private GovernedToolResourcePolicy.Snapshot policy() {
        return new GovernedToolResourcePolicy.Snapshot("wf","task","attempt","engineer","1.0",
            7,FUTURE,false,List.of(GRANT));
    }
    private GovernedToolResourcePolicy.Request request(String resource) {
        return new GovernedToolResourcePolicy.Request("wf","task","attempt","engineer","1.0",
            "storage","read",resource);
    }
    @Test void durableApprovalAndExactResourceAcrossRestart() throws Exception {
        Path db = temp.resolve("policy.sqlite");
        try (var store = new GovernedToolResourcePolicyStore(db)) {
            store.approve(policy());
            store.approve(policy()); // exact idempotency
        }
        try (var store = new GovernedToolResourcePolicyStore(db)) {
            assertTrue(store.evaluate(request(GRANT.resource()),7,Instant.now()).allowed());
            assertEquals(GovernedToolResourcePolicy.Denial.NOT_GRANTED,
                store.evaluate(request("artifact://project/b"),7,Instant.now()).denial());
            assertEquals(GovernedToolResourcePolicy.Denial.REVISION_MISMATCH,
                store.evaluate(request(GRANT.resource()),8,Instant.now()).denial());
        }
    }
    @Test void revokedAcrossConnectionsAndCannotBeReapproved() throws Exception {
        Path db = temp.resolve("policy.sqlite");
        try (var a = new GovernedToolResourcePolicyStore(db);
             var b = new GovernedToolResourcePolicyStore(db)) {
            a.approve(policy());
            assertTrue(b.evaluate(request(GRANT.resource()),7,Instant.now()).allowed());
            a.revoke("wf","task","attempt");
            assertEquals(GovernedToolResourcePolicy.Denial.REVOKED,
                b.evaluate(request(GRANT.resource()),7,Instant.now()).denial());
            assertThrows(SecurityException.class, () -> a.approve(policy()));
            a.revoke("wf","task","attempt");
        }
    }
    @Test void noImplicitGrantAndImmutableIdentity() throws Exception {
        try (var store = new GovernedToolResourcePolicyStore(temp.resolve("policy.sqlite"))) {
            assertFalse(store.evaluate(request(GRANT.resource()),7,Instant.now()).allowed());
            store.approve(policy());
            var modified = new GovernedToolResourcePolicy.Snapshot("wf","task","attempt",
                "engineer","2.0",7,FUTURE,false,List.of(GRANT));
            assertThrows(SecurityException.class, () -> store.approve(modified));
            assertThrows(SecurityException.class, () -> store.revoke("missing","task","attempt"));
            assertTrue(store.evaluate(request(GRANT.resource()),7,Instant.now()).allowed());
        }
    }
    @Test void expiryAndAttemptMismatchFailClosed() throws Exception {
        try (var store = new GovernedToolResourcePolicyStore(temp.resolve("policy.sqlite"))) {
            store.approve(policy());
            assertEquals(GovernedToolResourcePolicy.Denial.EXPIRED,
                store.evaluate(request(GRANT.resource()),7,FUTURE).denial());
            var forged = new GovernedToolResourcePolicy.Request("wf","task","other","engineer","1.0",
                "storage","read",GRANT.resource());
            assertFalse(store.evaluate(forged,7,Instant.now()).allowed());
        }
    }
}
