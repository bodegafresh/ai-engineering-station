package dev.aies.control.specialists;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/**
 * H01-C3 read-only diagnostic. No repairs, publication, or API exposure.
 * All checks run on one SQLite read snapshot; unexpected schema fails closed.
 */
public final class SpecialistGovernanceReconciler {
    private SpecialistGovernanceReconciler() {}
    public record Report(long decisions, long commands, long outbox, long baseline) {}
    public static Report verify(Path path) throws SQLException {
        Objects.requireNonNull(path);
        try (Connection c = new org.sqlite.JDBC().connect(
                "jdbc:sqlite:file:" + path.toAbsolutePath().normalize() + "?mode=ro",
                new Properties())) {
            if (c == null) throw new SQLException("SQLite unavailable");
            try (Statement s=c.createStatement()) {
                s.execute("PRAGMA query_only=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            c.setAutoCommit(false);
            try {
                // Establish the read snapshot before any integrity query.
                long decisions=count(c,"SELECT count(*) FROM specialist_registry_decisions");
                long commands=count(c,"SELECT count(*) FROM specialist_governance_idempotency");
                long outbox=count(c,"SELECT count(*) FROM specialist_governance_outbox");
                long baseline=count(c,"SELECT count(*) FROM specialist_governance_outbox_baseline");
                check(c, "SELECT count(*) FROM specialist_registry_decisions d LEFT JOIN specialist_governance_outbox o ON o.decision_seq=d.seq WHERE o.decision_seq IS NULL OR o.id IS NOT d.id OR o.version IS NOT d.version OR o.revision IS NOT d.to_revision OR o.action IS NOT d.action OR o.manifest_sha256 IS NOT d.manifest_sha256 OR o.actor IS NOT d.actor OR o.created_at IS NOT d.created_at");
                check(c, "SELECT count(*) FROM specialist_governance_outbox o LEFT JOIN specialist_registry_decisions d ON d.seq=o.decision_seq WHERE d.seq IS NULL");
                check(c, "SELECT count(*) FROM specialist_governance_outbox_baseline b LEFT JOIN specialist_registry_decisions d ON d.seq=b.decision_seq WHERE d.seq IS NULL");
                check(c, "SELECT count(*) FROM specialist_governance_idempotency i LEFT JOIN specialist_registry_decisions d ON d.id=i.id AND d.version=i.version AND d.to_revision=i.result_revision WHERE d.seq IS NULL OR d.action IS NOT i.action OR d.actor IS NOT i.actor OR d.manifest_sha256 IS NOT i.expected_hash OR d.to_status IS NOT i.state OR d.from_revision IS NOT i.expected_revision");
                // One decision can be created without a command key: reverse correspondence is not mandatory.
                if (decisions != outbox || baseline > outbox)
                    throw new SecurityException("Governance reconciliation mismatch");
                c.commit();
                return new Report(decisions,commands,outbox,baseline);
            } catch (SQLException | RuntimeException e) {
                try {c.rollback();} catch(SQLException rollback) {e.addSuppressed(rollback);}
                throw e;
            }
        }
    }
    private static void check(Connection c,String query) throws SQLException {
        if(count(c,query)!=0) throw new SecurityException("Governance reconciliation mismatch");
    }
    private static long count(Connection c,String query) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(query)) {
            if(!r.next()) throw new SQLException("No diagnostic result");
            return r.getLong(1);
        }
    }
}
