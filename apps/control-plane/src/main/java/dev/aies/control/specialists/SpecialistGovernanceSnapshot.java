package dev.aies.control.specialists;

import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.Properties;

/** Read-only, single-transaction snapshot of decision/outbox correspondence. */
public final class SpecialistGovernanceSnapshot {
    private SpecialistGovernanceSnapshot() {}

    public record Counts(long decisions, long outbox, long baseline, long dispatchable) {}

    public static Counts verify(Path path) throws SQLException {
        Objects.requireNonNull(path, "path");
        try (Connection db = new org.sqlite.JDBC().connect(
                "jdbc:sqlite:" + path.toAbsolutePath().normalize(), new Properties())) {
            if (db == null) throw new SQLException("SQLite unavailable");
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA query_only=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            db.setAutoCommit(false);
            try {
                // First SELECT establishes a consistent SQLite snapshot for all subsequent reads.
                long decisions = scalar(db, "SELECT count(*) FROM specialist_registry_decisions");
                long outbox = scalar(db, "SELECT count(*) FROM specialist_governance_outbox");
                boolean baselineExists = scalar(db, "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='specialist_governance_outbox_baseline'") == 1;
                long baseline = baselineExists ? scalar(db, "SELECT count(*) FROM specialist_governance_outbox_baseline") : 0;
                long missing = scalar(db, "SELECT count(*) FROM specialist_registry_decisions d LEFT JOIN specialist_governance_outbox o ON o.decision_seq=d.seq WHERE o.decision_seq IS NULL OR o.id IS NOT d.id OR o.version IS NOT d.version OR o.revision IS NOT d.to_revision OR o.action IS NOT d.action OR o.manifest_sha256 IS NOT d.manifest_sha256 OR o.actor IS NOT d.actor OR o.created_at IS NOT d.created_at");
                long orphan = scalar(db, "SELECT count(*) FROM specialist_governance_outbox o LEFT JOIN specialist_registry_decisions d ON d.seq=o.decision_seq WHERE d.seq IS NULL");
                long invalidBaseline = baselineExists ? scalar(db, "SELECT count(*) FROM specialist_governance_outbox_baseline b LEFT JOIN specialist_governance_outbox o ON o.decision_seq=b.decision_seq WHERE o.decision_seq IS NULL") : 0;
                if (missing != 0 || orphan != 0 || invalidBaseline != 0 || decisions != outbox)
                    throw new SecurityException("Governance snapshot integrity violation");
                long dispatchable = baselineExists ? scalar(db, "SELECT count(*) FROM specialist_governance_outbox o WHERE NOT EXISTS (SELECT 1 FROM specialist_governance_outbox_baseline b WHERE b.decision_seq=o.decision_seq)") : outbox;
                db.commit();
                return new Counts(decisions, outbox, baseline, dispatchable);
            } catch (SQLException | RuntimeException e) {
                try { db.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
                throw e;
            }
        }
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new SQLException("Missing scalar result");
            return r.getLong(1);
        }
    }
}
