package dev.aies.control.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedPolicyConcurrentStoreTest {
    @TempDir Path temp;
    @Test void conflictingApprovalsAcrossIndependentConnectionsCannotBothWin() throws Exception {
        var db=temp.resolve("policy.sqlite");
        var grant=new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:engineer");
        var first=new GovernedToolResourcePolicy.Snapshot("wf","task","attempt","engineer","1.0",
            1,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(grant));
        var second=new GovernedToolResourcePolicy.Snapshot("wf","task","attempt","engineer","1.0",
            2,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(grant));
        // Initialize WAL schema before competing writes; DDL setup is not part of the race.
        try(var initialized=new GovernedToolResourcePolicyStore(db)) { }
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->attempt(db,first,start));
            var b=executor.submit(()->attempt(db,second,start));
            start.countDown();
            boolean aa=a.get(15,TimeUnit.SECONDS), bb=b.get(15,TimeUnit.SECONDS);
            assertNotEquals(aa,bb,"Exactly one conflicting immutable policy may win");
        }
        try(var store=new GovernedToolResourcePolicyStore(db)) {
            var events=store.auditTrail("wf","task","attempt");
            assertEquals(1,events.stream().filter(e->"APPROVED".equals(e.decision())).count());
        }
    }
    private static boolean attempt(Path db,GovernedToolResourcePolicy.Snapshot policy,CountDownLatch start)
            throws Exception {
        start.await();
        try(var store=new GovernedToolResourcePolicyStore(db)) {
            try {store.approve(policy);return true;}
            catch(SecurityException denied){return false;}
        }
    }
}
