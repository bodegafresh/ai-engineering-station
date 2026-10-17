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

/**
 * H02-D2C2f.12q: bounded two-connection publication/revocation race contract.
 * Not a process crash, SQLite COMMIT injection, or filesystem durability proof.
 */
class GovernedPublicationConcurrencyTest {
    @TempDir Path temp;
    private static final String WF="wf", TASK="task", ATTEMPT="attempt";
    private static GovernedToolResourcePolicy.Request request() {
        return new GovernedToolResourcePolicy.Request(WF,TASK,ATTEMPT,"engineer","1.0",
            "workspace-documentation","invoke-model","ollama:software-engineer");
    }
    private static GovernedToolResourcePolicy.Snapshot snapshot() {
        return new GovernedToolResourcePolicy.Snapshot(WF,TASK,ATTEMPT,"engineer","1.0",7,
            Instant.parse("2099-01-01T00:00:00Z"),false,List.of(
                new GovernedToolResourcePolicy.Grant("workspace-documentation","invoke-model","ollama:software-engineer")));
    }
    @Test void revocationCommittedFirstDeniesAdmissionAcrossConnections() throws Exception {
        Path file=temp.resolve("policy.sqlite");
        try(var setup=new GovernedToolResourcePolicyStore(file)) {setup.approve(snapshot());}
        try(var first=new GovernedToolResourcePolicyStore(file);
            var second=new GovernedToolResourcePolicyStore(file)) {
            var start=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var revoke=pool.submit(()->{
                    start.await();
                    first.revoke(WF,TASK,ATTEMPT);
                    return true;
                });
                start.countDown();
                assertTrue(revoke.get(10,TimeUnit.SECONDS));
                var admit=pool.submit(()->assertThrows(SecurityException.class,
                    ()->second.admitPublication(request())));
                assertNotNull(admit.get(10,TimeUnit.SECONDS));
                assertFalse(second.publicationAdmitted(WF,TASK,ATTEMPT));
            }
        }
        try(var reopened=new GovernedToolResourcePolicyStore(file)) {
            assertFalse(reopened.publicationAdmitted(WF,TASK,ATTEMPT));
        }
    }
    @Test void admissionCommittedFirstSurvivesLaterRevocationAcrossConnections() throws Exception {
        Path file=temp.resolve("policy.sqlite");
        try(var setup=new GovernedToolResourcePolicyStore(file)) {setup.approve(snapshot());}
        try(var admitted=new GovernedToolResourcePolicyStore(file);
            var revoked=new GovernedToolResourcePolicyStore(file)) {
            try(var pool=Executors.newFixedThreadPool(2)) {
                var admit=pool.submit(()->{admitted.admitPublication(request());return true;});
                assertTrue(admit.get(10,TimeUnit.SECONDS));
                var revoke=pool.submit(()->{revoked.revoke(WF,TASK,ATTEMPT);return true;});
                assertTrue(revoke.get(10,TimeUnit.SECONDS));
                assertTrue(revoked.publicationAdmitted(WF,TASK,ATTEMPT));
                assertThrows(SecurityException.class,()->admitted.admitPublication(request()));
            }
        }
        try(var reopened=new GovernedToolResourcePolicyStore(file)) {
            assertTrue(reopened.publicationAdmitted(WF,TASK,ATTEMPT));
        }
    }
    @Test void simultaneousAdmissionAndRevocationHaveOnlyTwoAllowedOutcomes() throws Exception {
        Path file=temp.resolve("policy.sqlite");
        try(var setup=new GovernedToolResourcePolicyStore(file)) {setup.approve(snapshot());}
        try(var admission=new GovernedToolResourcePolicyStore(file);
            var revocation=new GovernedToolResourcePolicyStore(file)) {
            var gate=new CountDownLatch(1);
            try(var pool=Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()->{
                    gate.await();
                    try { admission.admitPublication(request());return true; }
                    catch(SecurityException denied) { return false; }
                });
                var r=pool.submit(()->{gate.await();revocation.revoke(WF,TASK,ATTEMPT);return true;});
                gate.countDown();
                boolean success=a.get(10,TimeUnit.SECONDS);
                assertTrue(r.get(10,TimeUnit.SECONDS));
                assertEquals(success,revocation.publicationAdmitted(WF,TASK,ATTEMPT),
                    "Only admission-before-revocation may leave a durable admission");
            }
        }
    }
}
