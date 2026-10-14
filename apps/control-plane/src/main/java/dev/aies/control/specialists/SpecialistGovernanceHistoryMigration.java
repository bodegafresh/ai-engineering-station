package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

/**
 * Explicit, offline, operator-approved migration of pre-outbox governance decisions.
 * Historical rows are marked as baseline and MUST NOT be dispatched by future consumers.
 * Does not publish, retry, or replay decisions.
 */
public final class SpecialistGovernanceHistoryMigration {
    private SpecialistGovernanceHistoryMigration() {}

    public record Result(long historicalRows, boolean alreadyApplied) {}

    public static Result migrate(Path file, ObjectMapper mapper) throws Exception {
        Objects.requireNonNull(file);
        Objects.requireNonNull(mapper);
        // Reject a corrupt source before any schema changes.
        SpecialistGovernanceIntegrity.verify(file, mapper);
        try (Connection db = new org.sqlite.JDBC().connect(
                "jdbc:sqlite:" + file.toAbsolutePath().normalize(), new Properties())) {
            if (db == null) throw new SQLException("SQLite unavailable");
            try (Statement s=db.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("BEGIN IMMEDIATE");
                try {
                    // Prevent an ambiguous mixed history from being silently accepted.
                    boolean hasOutbox = exists(db, "specialist_governance_outbox");
                    boolean hasBaseline = exists(db, "specialist_governance_outbox_baseline");
                    if (hasOutbox && !hasBaseline) {
                        long decisions = count(db,"specialist_registry_decisions");
                        long events = count(db,"specialist_governance_outbox");
                        if (decisions != events)
                            throw new SQLException("Partial outbox requires manual reconciliation");
                        // An existing complete outbox must not be relabeled historical.
                        s.execute("COMMIT");
                        return new Result(0, true);
                    }
                    s.execute("CREATE TABLE IF NOT EXISTS specialist_governance_outbox (" +
                        "decision_seq INTEGER PRIMARY KEY, id TEXT NOT NULL, version TEXT NOT NULL, " +
                        "revision INTEGER NOT NULL, action TEXT NOT NULL, manifest_sha256 TEXT NOT NULL, " +
                        "actor TEXT NOT NULL, created_at TEXT NOT NULL, " +
                        "FOREIGN KEY(decision_seq) REFERENCES specialist_registry_decisions(seq))");
                    s.execute("CREATE TABLE IF NOT EXISTS specialist_governance_outbox_baseline (" +
                        "decision_seq INTEGER PRIMARY KEY, migrated_at TEXT NOT NULL, " +
                        "FOREIGN KEY(decision_seq) REFERENCES specialist_registry_decisions(seq))");
                    long baseline = count(db,"specialist_governance_outbox_baseline");
                    if (hasBaseline) {
                        // Migration is strictly once-only. Existing baseline is not extended on rerun.
                        s.execute("COMMIT");
                        return new Result(baseline, true);
                    }
                    // SQL copy is atomic with the baseline marker and trigger creation.
                    try (PreparedStatement p=db.prepareStatement(
                            "INSERT INTO specialist_governance_outbox " +
                            "(decision_seq,id,version,revision,action,manifest_sha256,actor,created_at) " +
                            "SELECT seq,id,version,to_revision,action,manifest_sha256,actor,created_at " +
                            "FROM specialist_registry_decisions")) {
                        p.executeUpdate();
                    }
                    try (PreparedStatement p=db.prepareStatement(
                            "INSERT INTO specialist_governance_outbox_baseline(decision_seq,migrated_at) " +
                            "SELECT seq,? FROM specialist_registry_decisions")) {
                        p.setString(1, Instant.now().toString());
                        p.executeUpdate();
                    }
                    s.execute("CREATE TRIGGER IF NOT EXISTS specialist_governance_outbox_insert " +
                        "AFTER INSERT ON specialist_registry_decisions BEGIN " +
                        "INSERT INTO specialist_governance_outbox " +
                        "(decision_seq,id,version,revision,action,manifest_sha256,actor,created_at) " +
                        "VALUES(NEW.seq,NEW.id,NEW.version,NEW.to_revision,NEW.action," +
                        "NEW.manifest_sha256,NEW.actor,NEW.created_at); END");
                    s.execute("COMMIT");
                    return new Result(count(db,"specialist_governance_outbox_baseline"),false);
                } catch (Exception | Error failure) {
                    try { s.execute("ROLLBACK"); } catch (SQLException e) { failure.addSuppressed(e); }
                    throw failure;
                }
            }
        }
    }

    /** Future consumers must select only rows NOT in the baseline table. */
    public static long dispatchableCount(Path file) throws Exception {
        try (Connection db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath().normalize());
             Statement s=db.createStatement();
             ResultSet r=s.executeQuery(
                 "SELECT count(*) FROM specialist_governance_outbox o " +
                 "WHERE NOT EXISTS (SELECT 1 FROM specialist_governance_outbox_baseline b " +
                 "WHERE b.decision_seq=o.decision_seq)")) {
            if (!r.next()) throw new SQLException("Missing count");
            return r.getLong(1);
        }
    }

    private static boolean exists(Connection db,String table) throws SQLException {
        try (PreparedStatement p=db.prepareStatement(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")) {
            p.setString(1,table);
            try(ResultSet r=p.executeQuery()) {return r.next() && r.getLong(1)==1;}
        }
    }
    private static long count(Connection db,String table) throws SQLException {
        // All identifiers are fixed internal literals, never user input.
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)) {
            if(!r.next()) throw new SQLException("Missing table count");
            return r.getLong(1);
        }
    }
}
