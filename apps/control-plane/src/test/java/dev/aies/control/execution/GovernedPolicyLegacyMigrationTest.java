package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Additive migration from a pre-fingerprint/pre-policy-revision SQLite fixture. */
class GovernedPolicyLegacyMigrationTest {
    @TempDir Path dir;

    @Test void legacyAttemptRowsRemainUntrustedAfterMigrationAndReopen() throws Exception {
        Path dbFile=dir.resolve("legacy-handoff.sqlite");
        try(Connection db=new org.sqlite.JDBC().connect("jdbc:sqlite:"+dbFile,new java.util.Properties());
            var statement=db.createStatement()) {
            statement.execute("CREATE TABLE execution_governed_attempts("+
                "workflow_id TEXT NOT NULL,task_id TEXT NOT NULL,attempt_id TEXT NOT NULL,"+
                "specialist_id TEXT NOT NULL,specialist_version TEXT NOT NULL,"+
                "capability TEXT NOT NULL,bound_at TEXT NOT NULL,"+
                "PRIMARY KEY(workflow_id,task_id,attempt_id))");
            statement.execute("INSERT INTO execution_governed_attempts VALUES("+
                "'wf-execution','a','legacy-token','software-engineer','1.0','software',"+
                "'2025-01-01T00:00:00Z')");
        }
        for(int i=0;i<2;i++) {
            try(var store=new ExecutionHandoffStore(dbFile,new ObjectMapper())) { /* schema migration */ }
            try(Connection db=new org.sqlite.JDBC().connect("jdbc:sqlite:"+dbFile,new java.util.Properties());
                var statement=db.createStatement()) {
                Set<String> columns=new HashSet<>();
                try(ResultSet rs=statement.executeQuery("PRAGMA table_info(execution_governed_attempts)")) {
                    while(rs.next()) columns.add(rs.getString("name"));
                }
                assertTrue(columns.containsAll(Set.of("manifest_sha256","registry_revision","policy_revision")));
                try(ResultSet rs=statement.executeQuery("SELECT manifest_sha256,registry_revision,policy_revision " +
                    "FROM execution_governed_attempts WHERE attempt_id='legacy-token'")) {
                    assertTrue(rs.next(),"Historical row must be preserved");
                    assertNull(rs.getObject(1),"Never fabricate manifest fingerprint");
                    assertNull(rs.getObject(2),"Never fabricate registry revision");
                    assertNull(rs.getObject(3),"Never fabricate policy revision");
                    assertFalse(rs.next());
                }
            }
        }
    }
}
