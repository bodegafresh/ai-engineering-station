package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

/** H02-D2C2d: attempt identity survives restart, but an old lease never becomes a grant. */
class ExecutionGovernedAttemptTest {
    @TempDir Path dir;
    private ExecutionHandoffStore.GovernedAttempt attempt(String token, String version) {
        return new ExecutionHandoffStore.GovernedAttempt("wf-execution", "a", token,
            "software-engineer", version, "software", "a".repeat(64), 7);
    }

    @Test void persistsIdentityAndRejectsForgedOrMissingLeaseAfterRestart() throws Exception {
        Path file=dir.resolve("handoff.sqlite");
        String token;
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            token=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            assertTrue(store.bindGovernedAttempt(attempt(token,"1.0")));
            assertTrue(store.bindGovernedAttempt(attempt(token,"1.0")));
            assertThrows(SecurityException.class,()->store.requireNoGovernedHistory("wf-execution","a"));
            assertThrows(SecurityException.class,()->store.requireGovernedAttempt(attempt(token,"2.0")));
            assertThrows(SecurityException.class,()->store.bindGovernedAttempt(attempt(token,"2.0")));
            assertThrows(SecurityException.class,()->store.requireGovernedAttempt(attempt("fake-token","1.0")));
        }
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            assertDoesNotThrow(()->store.requireGovernedAttempt(attempt(token,"1.0")));
            assertTrue(store.fail("wf-execution","a",token,true,3));
            assertThrows(SecurityException.class,()->store.requireGovernedAttempt(attempt(token,"1.0")));
            assertThrows(SecurityException.class,()->store.bindGovernedAttempt(attempt(token,"1.0")));
        }
    }

    @Test void versionCannotSilentlyUpgradeOnRetry() throws Exception {
        Path file=dir.resolve("handoff.sqlite");
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            String first=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            store.bindGovernedAttempt(attempt(first,"1.0"));
            assertTrue(store.fail("wf-execution","a",first,true,3));
            String second=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            assertNotEquals(first,second);
            assertThrows(SecurityException.class,()->store.bindGovernedAttempt(attempt(second,"2.0")));
            assertDoesNotThrow(()->store.bindGovernedAttempt(attempt(second,"1.0")));
        }
    }

    @Test void missingFingerprintAndRevisionFailClosed() throws Exception {
        Path file=dir.resolve("missing-fingerprint.sqlite");
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            String token=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            assertThrows(SecurityException.class, ()->store.bindGovernedAttempt(
                new ExecutionHandoffStore.GovernedAttempt("wf-execution","a",token,"software-engineer","1.0","software")));
            store.bindGovernedAttempt(attempt(token,"1.0"));
            assertThrows(SecurityException.class, ()->store.requireGovernedAttempt(
                new ExecutionHandoffStore.GovernedAttempt("wf-execution","a",token,"software-engineer","1.0","software","b".repeat(64),7)));
            assertThrows(SecurityException.class, ()->store.requireGovernedAttempt(
                new ExecutionHandoffStore.GovernedAttempt("wf-execution","a",token,"software-engineer","1.0","software","a".repeat(64),8)));
        }
    }

    @Test void expiredLeaseFailsClosed() throws Exception {
        Path file=dir.resolve("handoff.sqlite");
        String token;
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            token=store.claim("wf-execution",3,60).orElseThrow().leaseToken();
            store.bindGovernedAttempt(attempt(token,"1.0"));
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file);
            var stmt=db.createStatement()) {
            stmt.executeUpdate("UPDATE execution_tasks SET lease_until='2000-01-01T00:00:00Z'");
        }
        try(var store=new ExecutionHandoffStore(file,new ObjectMapper())) {
            assertThrows(SecurityException.class,()->store.requireGovernedAttempt(attempt(token,"1.0")));
        }
    }
}
