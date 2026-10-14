package dev.aies.control.specialists;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceSnapshotTest {
    @TempDir Path temp;

    @Test void detectsMissingOutboxWithoutMutatingHistory() throws Exception {
        Path file = temp.resolve("snapshot.sqlite");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = db.createStatement()) {
            s.execute("CREATE TABLE specialist_registry_decisions (seq INTEGER PRIMARY KEY, id TEXT, version TEXT, to_revision INTEGER, action TEXT, manifest_sha256 TEXT, actor TEXT, created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox (decision_seq INTEGER PRIMARY KEY, id TEXT, version TEXT, revision INTEGER, action TEXT, manifest_sha256 TEXT, actor TEXT, created_at TEXT)");
            assertEquals(0, SpecialistGovernanceSnapshot.verify(file).decisions());
            s.execute("INSERT INTO specialist_registry_decisions VALUES (1,'a','1',1,'VALIDATE','hash','operator','now')");
            assertThrows(SecurityException.class, () -> SpecialistGovernanceSnapshot.verify(file));
            s.execute("INSERT INTO specialist_governance_outbox VALUES (1,'a','1',1,'VALIDATE','hash','operator','now')");
            assertEquals(1, SpecialistGovernanceSnapshot.verify(file).dispatchable());
            s.execute("CREATE TABLE specialist_governance_outbox_baseline (decision_seq INTEGER PRIMARY KEY, migrated_at TEXT)");
            s.execute("INSERT INTO specialist_governance_outbox_baseline VALUES (1,'now')");
            assertEquals(0, SpecialistGovernanceSnapshot.verify(file).dispatchable());
        }
    }

    @Test void rejectsOrphanBaseline() throws Exception {
        Path file = temp.resolve("orphan.sqlite");
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = db.createStatement()) {
            s.execute("CREATE TABLE specialist_registry_decisions (seq INTEGER PRIMARY KEY, id TEXT, version TEXT, to_revision INTEGER, action TEXT, manifest_sha256 TEXT, actor TEXT, created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox (decision_seq INTEGER PRIMARY KEY, id TEXT, version TEXT, revision INTEGER, action TEXT, manifest_sha256 TEXT, actor TEXT, created_at TEXT)");
            s.execute("CREATE TABLE specialist_governance_outbox_baseline (decision_seq INTEGER PRIMARY KEY, migrated_at TEXT)");
            s.execute("INSERT INTO specialist_governance_outbox_baseline VALUES (42,'now')");
            assertThrows(SecurityException.class, () -> SpecialistGovernanceSnapshot.verify(file));
        }
    }
}
