package dev.aies.control.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Durable, operator-owned, per-attempt tool grants. Not an LLM-facing service.
 * A grant is immutable: changing permissions requires a new attempt and operator approval.
 * Revocation is durable and irreversible. A successful check is not a reservation of
 * an external resource: callers must check at their actual point of use.
 */
public final class GovernedToolResourcePolicyStore implements AutoCloseable {
    private final Connection db;

    public GovernedToolResourcePolicyStore(Path file) throws Exception {
        Path normalized = Objects.requireNonNull(file).toAbsolutePath().normalize();
        Files.createDirectories(normalized.getParent());
        db = DriverManager.getConnection("jdbc:sqlite:" + normalized);
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS governed_tool_policies (" +
                "workflow_id TEXT NOT NULL, task_id TEXT NOT NULL, attempt_id TEXT NOT NULL," +
                "specialist_id TEXT NOT NULL, specialist_version TEXT NOT NULL," +
                "policy_revision INTEGER NOT NULL CHECK(policy_revision>=0)," +
                "expires_at TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1))," +
                "created_at TEXT NOT NULL, revoked_at TEXT," +
                "PRIMARY KEY(workflow_id,task_id,attempt_id))");
            s.execute("CREATE TABLE IF NOT EXISTS governed_tool_policy_grants (" +
                "workflow_id TEXT NOT NULL, task_id TEXT NOT NULL, attempt_id TEXT NOT NULL," +
                "tool TEXT NOT NULL, action TEXT NOT NULL, resource TEXT NOT NULL," +
                "PRIMARY KEY(workflow_id,task_id,attempt_id,tool,action,resource)," +
                "FOREIGN KEY(workflow_id,task_id,attempt_id) REFERENCES " +
                "governed_tool_policies(workflow_id,task_id,attempt_id) ON DELETE RESTRICT)");
        }
    }

    /** Called only after a trusted operator approves the exact immutable snapshot. */
    public synchronized void approve(GovernedToolResourcePolicy.Snapshot snapshot) throws Exception {
        Objects.requireNonNull(snapshot);
        if (snapshot.revoked() || !Instant.now().isBefore(snapshot.expiresAt()))
            throw new SecurityException("Cannot approve revoked or expired policy");
        begin();
        try {
            var previous = read(snapshot.workflowId(), snapshot.taskId(), snapshot.attemptId());
            if (previous != null) {
                if (!previous.equals(snapshot)) throw new SecurityException("Policy mutation or revoked replay");
                commit();
                return;
            }
            try (var p = db.prepareStatement("INSERT INTO governed_tool_policies VALUES(?,?,?,?,?,?,?,?,?,NULL)")) {
                p.setString(1,snapshot.workflowId()); p.setString(2,snapshot.taskId());
                p.setString(3,snapshot.attemptId()); p.setString(4,snapshot.specialistId());
                p.setString(5,snapshot.specialistVersion()); p.setLong(6,snapshot.policyRevision());
                p.setString(7,snapshot.expiresAt().toString()); p.setInt(8,0);
                p.setString(9,Instant.now().toString()); p.executeUpdate();
            }
            try (var p = db.prepareStatement("INSERT INTO governed_tool_policy_grants VALUES(?,?,?,?,?,?)")) {
                for (var grant : snapshot.grants()) {
                    p.setString(1,snapshot.workflowId()); p.setString(2,snapshot.taskId());
                    p.setString(3,snapshot.attemptId()); p.setString(4,grant.tool());
                    p.setString(5,grant.action()); p.setString(6,grant.resource()); p.addBatch();
                }
                p.executeBatch();
            }
            commit();
        } catch (Exception e) { rollback(e); throw e; }
    }

    /** Irreversible, idempotent operator revocation. Unknown policies fail closed. */
    public synchronized void revoke(String workflowId, String taskId, String attemptId) throws Exception {
        begin();
        try {
            try (var p = db.prepareStatement("UPDATE governed_tool_policies SET revoked=1, " +
                    "revoked_at=COALESCE(revoked_at,?) WHERE workflow_id=? AND task_id=? AND attempt_id=?")) {
                p.setString(1,Instant.now().toString()); p.setString(2,workflowId);
                p.setString(3,taskId); p.setString(4,attemptId);
                if (p.executeUpdate() != 1) throw new SecurityException("Unknown policy");
            }
            commit();
        } catch (Exception e) { rollback(e); throw e; }
    }

    /** Always reads the current SQLite state; never caches a grant. */
    public synchronized GovernedToolResourcePolicy.Decision evaluate(
            GovernedToolResourcePolicy.Request request, long expectedRevision, Instant now) throws Exception {
        Objects.requireNonNull(request);
        Objects.requireNonNull(now);
        var snapshot = read(request.workflowId(), request.taskId(), request.attemptId());
        if (snapshot == null) return new GovernedToolResourcePolicy.Decision(false,
            GovernedToolResourcePolicy.Denial.NOT_GRANTED);
        return GovernedToolResourcePolicy.evaluate(request, snapshot, expectedRevision, now);
    }

    /** Optional durable lease/identity barrier for governed execution consumers. */
    public synchronized void requireAuthorized(ExecutionHandoffStore handoff,
            ExecutionHandoffStore.GovernedAttempt attempt,
            GovernedToolResourcePolicy.Request request, long revision) throws Exception {
        Objects.requireNonNull(handoff);
        Objects.requireNonNull(attempt);
        if (!attempt.workflowId().equals(request.workflowId()) ||
            !attempt.taskId().equals(request.taskId()) ||
            !attempt.attemptId().equals(request.attemptId()) ||
            !attempt.specialistId().equals(request.specialistId()) ||
            !attempt.version().equals(request.specialistVersion()))
            throw new SecurityException("Attempt identity mismatch");
        handoff.requireGovernedAttempt(attempt);
        var decision = evaluate(request, revision, Instant.now());
        if (!decision.allowed()) throw new SecurityException("Tool authorization denied: " + decision.denial());
    }

    /** Per-attempt immutable revision lookup followed by fresh lease and grant verification.
     * Revocation or expiry between reads is denied by the second check.
     */
    public synchronized void requireAuthorizedCurrent(ExecutionHandoffStore handoff,
            ExecutionHandoffStore.GovernedAttempt attempt,
            GovernedToolResourcePolicy.Request request) throws Exception {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(request);
        var current = read(attempt.workflowId(), attempt.taskId(), attempt.attemptId());
        if (current == null) throw new SecurityException("Missing operator-approved tool policy");
        requireAuthorized(handoff, attempt, request, current.policyRevision());
    }

    private GovernedToolResourcePolicy.Snapshot read(String wf, String task, String attempt) throws Exception {
        try (var p = db.prepareStatement("SELECT specialist_id,specialist_version,policy_revision,expires_at,revoked " +
                "FROM governed_tool_policies WHERE workflow_id=? AND task_id=? AND attempt_id=?")) {
            p.setString(1,wf); p.setString(2,task); p.setString(3,attempt);
            try (var rs = p.executeQuery()) {
                if (!rs.next()) return null;
                String specialist = rs.getString(1), version = rs.getString(2);
                long revision = rs.getLong(3);
                Instant expires = Instant.parse(rs.getString(4));
                boolean revoked = rs.getInt(5) == 1;
                List<GovernedToolResourcePolicy.Grant> grants = new ArrayList<>();
                try (var q = db.prepareStatement("SELECT tool,action,resource FROM governed_tool_policy_grants " +
                        "WHERE workflow_id=? AND task_id=? AND attempt_id=? ORDER BY tool,action,resource")) {
                    q.setString(1,wf); q.setString(2,task); q.setString(3,attempt);
                    try (var gr = q.executeQuery()) {
                        while (gr.next()) grants.add(new GovernedToolResourcePolicy.Grant(
                            gr.getString(1),gr.getString(2),gr.getString(3)));
                    }
                }
                return new GovernedToolResourcePolicy.Snapshot(wf,task,attempt,specialist,version,
                    revision,expires,revoked,grants);
            }
        }
    }

    private void begin() throws SQLException {
        try (var s=db.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
    }
    private void commit() throws SQLException {
        try (var s=db.createStatement()) { s.execute("COMMIT"); }
    }
    private void rollback(Exception cause) {
        try (var s=db.createStatement()) { s.execute("ROLLBACK"); }
        catch (SQLException e) { cause.addSuppressed(e); }
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
