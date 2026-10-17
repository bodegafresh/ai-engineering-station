package dev.aies.control.execution;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQLite WAL rollback and ambiguous client-observation boundaries. */
class GovernedPublicationTransactionFaultTest {
    @TempDir Path temp;
    private static final String WF="wf", TASK="task", ATTEMPT="attempt";
    private GovernedToolResourcePolicy.Request request() {
        return new GovernedToolResourcePolicy.Request(WF,TASK,ATTEMPT,"engineer","1.0",
            "workspace-documentation","invoke-model","ollama:software-engineer");
    }
    private GovernedToolResourcePolicy.Snapshot policy() {
        return new GovernedToolResourcePolicy.Snapshot(WF,TASK,ATTEMPT,"engineer","1.0",7,
            Instant.parse("2099-01-01T00:00:00Z"),false,List.of(
                new GovernedToolResourcePolicy.Grant(
                    "workspace-documentation","invoke-model","ollama:software-engineer")));
    }

    /** An injected SQL constraint failure before COMMIT must roll back the admission and audit. */
    @Test void sqlFailureBeforeCommitCannotPersistPartialAdmission() throws Exception {
        Path path=temp.resolve("policies.sqlite");
        try(var store=new GovernedToolResourcePolicyStore(path)) {
            store.approve(policy());
        }
        // A trigger aborts INSERT after the ALLOW audit has been written in the same transaction.
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            var statement=db.createStatement()) {
            statement.execute("CREATE TRIGGER reject_admission BEFORE INSERT ON governed_publication_admissions "+
                "BEGIN SELECT RAISE(ABORT,'injected pre-commit failure'); END");
        }
        try(var store=new GovernedToolResourcePolicyStore(path)) {
            assertThrows(SQLException.class,()->store.admitPublication(request()));
            assertFalse(store.publicationAdmitted(WF,TASK,ATTEMPT));
            assertEquals(0,store.auditTrail(WF,TASK,ATTEMPT).stream()
                .filter(e->"PUBLICATION_ADMISSION".equals(e.type()) && "ALLOW".equals(e.decision()))
                .count(),"ALLOW audit must roll back with admission");
        }
        // Reopen to distinguish in-memory state from durable WAL state.
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path.toAbsolutePath());
            var statement=db.createStatement()) {
            statement.execute("DROP TRIGGER reject_admission");
        }
        try(var store=new GovernedToolResourcePolicyStore(path)) {
            assertFalse(store.publicationAdmitted(WF,TASK,ATTEMPT));
            store.admitPublication(request());
            assertTrue(store.publicationAdmitted(WF,TASK,ATTEMPT));
        }
    }

    /** A client that loses its response after a successful COMMIT must reconcile by reading. */
    @Test void lostCommitAcknowledgementRequiresReadBeforeRetry() throws Exception {
        Path path=temp.resolve("policies.sqlite");
        try(var store=new GovernedToolResourcePolicyStore(path)) {
            store.approve(policy());
            store.admitPublication(request());
            // Simulated lost acknowledgement: caller treats the successful return as unknown.
        }
        try(var restarted=new GovernedToolResourcePolicyStore(path)) {
            assertTrue(restarted.publicationAdmitted(WF,TASK,ATTEMPT));
            assertThrows(SecurityException.class,()->restarted.admitPublication(request()),
                "Admission is immutable; a blind retry must not create another admission");
            assertEquals(1,restarted.auditTrail(WF,TASK,ATTEMPT).stream()
                .filter(e->"PUBLICATION_ADMISSION".equals(e.type()) && "ALLOW".equals(e.decision()))
                .count());
        }
    }
}
