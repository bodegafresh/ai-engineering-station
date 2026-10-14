package dev.aies.control.specialists;

import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;

/**
 * S1-C.3 additive transactional outbox. A trigger inserts an event inside the
 * same SQLite transaction that records a governance decision.
 * No network publishing, no automatic replay or model execution.
 */
public final class SpecialistGovernanceOutbox {
    private SpecialistGovernanceOutbox() {}

    /** Must be called by trusted migration/operator startup before governance writes. */
    public static void install(Path file) throws Exception {
        Objects.requireNonNull(file);
        try (Connection db = open(file); Statement s = db.createStatement()) {
            // S1-B schema must already exist. Fail closed instead of silently creating it.
            try (ResultSet rs = db.getMetaData().getTables(null, null,
                    "specialist_registry_decisions", null)) {
                if (!rs.next()) throw new SQLException("Governance decisions schema is absent");
            }
            s.execute("CREATE TABLE IF NOT EXISTS specialist_governance_outbox (" +
                "decision_seq INTEGER PRIMARY KEY, id TEXT NOT NULL, version TEXT NOT NULL, " +
                "revision INTEGER NOT NULL, action TEXT NOT NULL, manifest_sha256 TEXT NOT NULL, " +
                "actor TEXT NOT NULL, created_at TEXT NOT NULL, " +
                "FOREIGN KEY(decision_seq) REFERENCES specialist_registry_decisions(seq))");
            // Idempotent DDL. Insert-only: no network side effects in a transaction.
            s.execute("CREATE TRIGGER IF NOT EXISTS specialist_governance_outbox_insert " +
                "AFTER INSERT ON specialist_registry_decisions BEGIN " +
                "INSERT INTO specialist_governance_outbox " +
                "(decision_seq,id,version,revision,action,manifest_sha256,actor,created_at) " +
                "VALUES(NEW.seq,NEW.id,NEW.version,NEW.to_revision,NEW.action," +
                "NEW.manifest_sha256,NEW.actor,NEW.created_at); END");
        }
    }

    /** Snapshot of pending events, with no claim of delivery or exactly-once effects. */
    public static long count(Path file) throws Exception {
        try (Connection db = open(file);
             Statement s = db.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM specialist_governance_outbox")) {
            if (!r.next()) throw new SQLException("Outbox query returned no row");
            return r.getLong(1);
        }
    }

    /** Detects missing/mismatched events for all decisions after installation. */
    public static void verify(Path file) throws Exception {
        try (Connection db = open(file); Statement s = db.createStatement();
             ResultSet r = s.executeQuery(
                 "SELECT count(*) FROM specialist_registry_decisions d " +
                 "LEFT JOIN specialist_governance_outbox o ON o.decision_seq=d.seq " +
                 "WHERE o.decision_seq IS NULL OR o.id<>d.id OR o.version<>d.version " +
                 "OR o.revision<>d.to_revision OR o.action<>d.action " +
                 "OR o.manifest_sha256<>d.manifest_sha256 OR o.actor<>d.actor " +
                 "OR o.created_at<>d.created_at")) {
            if (!r.next() || r.getLong(1)!=0) throw new SecurityException("Outbox integrity violation");
        }
        try (Connection db = open(file); Statement s = db.createStatement();
             ResultSet r = s.executeQuery(
                 "SELECT count(*) FROM specialist_governance_outbox o " +
                 "LEFT JOIN specialist_registry_decisions d ON d.seq=o.decision_seq " +
                 "WHERE d.seq IS NULL")) {
            if (!r.next() || r.getLong(1)!=0) throw new SecurityException("Orphan outbox entry");
        }
    }

    private static Connection open(Path file) throws SQLException {
        Connection db = new org.sqlite.JDBC().connect(
            "jdbc:sqlite:" + file.toAbsolutePath().normalize(), new Properties());
        if (db==null) throw new SQLException("SQLite unavailable");
        try (Statement s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
        } catch (SQLException e) { try { db.close(); } catch (SQLException close) { e.addSuppressed(close); } throw e; }
        return db;
    }
}
