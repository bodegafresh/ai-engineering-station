package dev.aies.control.specialists;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceReconcilerTest {
    @TempDir Path dir;
    @Test void missingDatabaseFailsWithoutCreatingFile() {
        Path db=dir.resolve("missing.sqlite");
        assertThrows(SQLException.class,()->SpecialistGovernanceReconciler.verify(db));
        assertFalse(Files.exists(db));
    }
    @Test void absentSchemaFailsClosed() throws Exception {
        Path db=dir.resolve("empty.sqlite");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db)) {}
        assertThrows(SQLException.class,()->SpecialistGovernanceReconciler.verify(db));
    }
    @Test void orphanCommandFailsClosed() throws Exception {
        Path db=dir.resolve("orphan.sqlite");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement()) {
            s.execute("CREATE TABLE specialist_registry_decisions(seq INTEGER,id TEXT,version TEXT,to_revision INTEGER,action TEXT,actor TEXT,manifest_sha256 TEXT,to_status TEXT,from_revision INTEGER,created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox(decision_seq INTEGER,id TEXT,version TEXT,revision INTEGER,action TEXT,actor TEXT,manifest_sha256 TEXT,created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox_baseline(decision_seq INTEGER)");
            s.execute("CREATE TABLE specialist_governance_idempotency(id TEXT,version TEXT,result_revision INTEGER,action TEXT,actor TEXT,expected_hash TEXT,state TEXT,expected_revision INTEGER)");
            s.execute("INSERT INTO specialist_governance_idempotency VALUES('x','1',1,'VALIDATE','actor','hash','VALIDATED',0)");
        }
        assertThrows(SecurityException.class,()->SpecialistGovernanceReconciler.verify(db));
    }
    @Test void emptyConsistentSchemaPasses() throws Exception {
        Path db=dir.resolve("consistent.sqlite");
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement()) {
            s.execute("CREATE TABLE specialist_registry_decisions(seq INTEGER,id TEXT,version TEXT,to_revision INTEGER,action TEXT,actor TEXT,manifest_sha256 TEXT,to_status TEXT,from_revision INTEGER,created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox(decision_seq INTEGER,id TEXT,version TEXT,revision INTEGER,action TEXT,actor TEXT,manifest_sha256 TEXT,created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox_baseline(decision_seq INTEGER)");
            s.execute("CREATE TABLE specialist_governance_idempotency(id TEXT,version TEXT,result_revision INTEGER,action TEXT,actor TEXT,expected_hash TEXT,state TEXT,expected_revision INTEGER)");
        }
        assertEquals(0,SpecialistGovernanceReconciler.verify(db).decisions());
    }
}
