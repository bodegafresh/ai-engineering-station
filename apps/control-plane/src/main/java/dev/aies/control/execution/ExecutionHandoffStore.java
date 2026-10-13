package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.planning.PlanningContracts;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Separate durable handoff journal; no automatic specialist execution. */
public final class ExecutionHandoffStore implements AutoCloseable {
    public record Work(String workflowId, String taskId, String specialistId, String status,
                       String leaseToken, String leaseUntil, int attempts, String evidenceRef) {}
    private final Connection db;
    private final ObjectMapper mapper;
    public ExecutionHandoffStore(Path file, ObjectMapper mapper) throws Exception {
        this.mapper = Objects.requireNonNull(mapper);
        var path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        db = new org.sqlite.JDBC().connect("jdbc:sqlite:" + path, new Properties());
        if (db == null) throw new SQLException("SQLite driver refused URL");
        try (var s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS execution_workflows(workflow_id TEXT PRIMARY KEY, plan_json TEXT NOT NULL, created_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS execution_tasks(workflow_id TEXT NOT NULL,task_id TEXT NOT NULL,specialist_id TEXT NOT NULL,status TEXT NOT NULL,lease_token TEXT,lease_until TEXT,attempts INTEGER NOT NULL DEFAULT 0,evidence_ref TEXT,updated_at TEXT NOT NULL,PRIMARY KEY(workflow_id,task_id),FOREIGN KEY(workflow_id) REFERENCES execution_workflows(workflow_id))");
            s.execute("CREATE INDEX IF NOT EXISTS execution_tasks_status_idx ON execution_tasks(workflow_id,status)");
        }
    }
    /** Idempotent import: same workflow + same plan is a no-op, changed plan is rejected. */
    public synchronized boolean importPlan(PlanningContracts.Plan plan) throws Exception {
        var ordered = ExecutionGraph.ordered(plan);
        var json = mapper.writeValueAsString(plan);
        db.setAutoCommit(false);
        try {
            try (var p = db.prepareStatement("SELECT plan_json FROM execution_workflows WHERE workflow_id=?")) {
                p.setString(1, plan.workflowId());
                try (var rs = p.executeQuery()) {
                    if (rs.next()) {
                        if (!json.equals(rs.getString(1))) throw new IllegalStateException("Conflicting execution plan");
                        db.commit(); return false;
                    }
                }
            }
            var now = Instant.now().toString();
            try (var p = db.prepareStatement("INSERT INTO execution_workflows VALUES(?,?,?)")) {
                p.setString(1,plan.workflowId());p.setString(2,json);p.setString(3,now);p.executeUpdate();
            }
            try (var p = db.prepareStatement("INSERT INTO execution_tasks(workflow_id,task_id,specialist_id,status,updated_at) VALUES(?,?,?,?,?)")) {
                for (var task : ordered) {
                    p.setString(1,plan.workflowId());p.setString(2,task.id());p.setString(3,task.specialistId());
                    p.setString(4,"PENDING");p.setString(5,now);p.addBatch();
                }
                p.executeBatch();
            }
            db.commit();return true;
        } catch (Exception e) { db.rollback();throw e; }
        finally {db.setAutoCommit(true);}
    }
    public synchronized List<Work> list(String workflowId) throws Exception {
        List<Work> result = new ArrayList<>();
        try (var p = db.prepareStatement("SELECT * FROM execution_tasks WHERE workflow_id=? ORDER BY task_id")) {
            p.setString(1,workflowId);
            try (var rs = p.executeQuery()) {
                while (rs.next()) result.add(new Work(workflowId,rs.getString("task_id"),rs.getString("specialist_id"),rs.getString("status"),rs.getString("lease_token"),rs.getString("lease_until"),rs.getInt("attempts"),rs.getString("evidence_ref")));
            }
        }
        return List.copyOf(result);
    }
    /** Explicit claim for an authorized future executor; never invokes tools. */
    public synchronized Optional<Work> claim(String workflowId, int maxAttempts, long leaseSeconds) throws Exception {
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 10 || leaseSeconds > 3600)
            throw new IllegalArgumentException("Invalid lease policy");
        db.setAutoCommit(false);
        try {
            PlanningContracts.Plan plan;
            try (var p = db.prepareStatement("SELECT plan_json FROM execution_workflows WHERE workflow_id=?")) {
                p.setString(1,workflowId);
                try (var rs = p.executeQuery()) {if (!rs.next()) throw new IllegalArgumentException("Unknown workflow");
                    plan = mapper.readValue(rs.getString(1),PlanningContracts.Plan.class);}
            }
            var states = new HashMap<String,String>();
            for (var w : list(workflowId)) states.put(w.taskId(),w.status());
            var now = Instant.now();
            for (var task : ExecutionGraph.ordered(plan)) {
                if (!task.dependencies().stream().allMatch(d -> "COMPLETED".equals(states.get(d)))) continue;
                var token = UUID.randomUUID().toString();
                try (var p = db.prepareStatement("UPDATE execution_tasks SET status='LEASED',lease_token=?,lease_until=?,attempts=attempts+1,updated_at=? WHERE workflow_id=? AND task_id=? AND attempts<? AND (status='PENDING' OR (status='LEASED' AND lease_until<?))")) {
                    p.setString(1,token);p.setString(2,now.plusSeconds(leaseSeconds).toString());p.setString(3,now.toString());
                    p.setString(4,workflowId);p.setString(5,task.id());p.setInt(6,maxAttempts);p.setString(7,now.toString());
                    if (p.executeUpdate() == 1) {
                        db.commit();
                        return list(workflowId).stream().filter(w -> w.taskId().equals(task.id())).findFirst();
                    }
                }
            }
            db.commit();return Optional.empty();
        } catch(Exception e) {db.rollback();throw e;}
        finally {db.setAutoCommit(true);}
    }
    /** Completion requires an evidence reference; never claims that a test passed by itself. */
    public synchronized boolean complete(String workflowId,String taskId,String token,String evidenceRef) throws Exception {
        if (token == null || token.isBlank() || evidenceRef == null ||
            !(evidenceRef.startsWith("artifact://") || evidenceRef.startsWith("https://")))
            throw new IllegalArgumentException("Evidence reference required");
        try (var p = db.prepareStatement("UPDATE execution_tasks SET status='COMPLETED',evidence_ref=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE workflow_id=? AND task_id=? AND status='LEASED' AND lease_token=? AND lease_until>?")) {
            p.setString(1,evidenceRef);p.setString(2,Instant.now().toString());p.setString(3,workflowId);p.setString(4,taskId);p.setString(5,token);p.setString(6,Instant.now().toString());
            return p.executeUpdate() == 1;
        }
    }
    /** Idempotent lease-token guarded failure. Retryable work is requeued; terminal work is dead-lettered. */
    public synchronized boolean fail(String workflowId,String taskId,String token,boolean retryable,int maxAttempts) throws Exception {
        if (maxAttempts < 1 || maxAttempts > 20 || token == null || token.isBlank())
            throw new IllegalArgumentException("Invalid failure command");
        String status = retryable ? "PENDING" : "DEAD";
        try (var p = db.prepareStatement("UPDATE execution_tasks SET status=CASE WHEN attempts>=? THEN 'DEAD' ELSE ? END,lease_token=NULL,lease_until=NULL,updated_at=? WHERE workflow_id=? AND task_id=? AND status='LEASED' AND lease_token=? AND lease_until>?")) {
            p.setInt(1,maxAttempts);p.setString(2,status);p.setString(3,Instant.now().toString());
            p.setString(4,workflowId);p.setString(5,taskId);p.setString(6,token);p.setString(7,Instant.now().toString());
            return p.executeUpdate()==1;
        }
    }
    /** Read the immutable imported plan for execution; no planning DB dependency. */
    public synchronized PlanningContracts.Plan plan(String workflowId) throws Exception {
        dev.aies.control.communication.CommunicationContracts.id(workflowId);
        try (var p=db.prepareStatement("SELECT plan_json FROM execution_workflows WHERE workflow_id=?")) {
            p.setString(1,workflowId);
            try(var rs=p.executeQuery()) {
                if(!rs.next())throw new IllegalArgumentException("Unknown workflow");
                return mapper.readValue(rs.getString(1),PlanningContracts.Plan.class);
            }
        }
    }
    /** Persist actual artifact reference, but do not unlock DAG until quality approval. */
    public synchronized boolean stageEvidence(String workflowId,String taskId,String token,String artifactRecordId) throws Exception {
        dev.aies.control.communication.CommunicationContracts.id(workflowId);dev.aies.control.communication.CommunicationContracts.id(taskId);dev.aies.control.communication.CommunicationContracts.id(artifactRecordId);
        if(token==null||token.isBlank())throw new IllegalArgumentException("Missing lease token");
        try(var p=db.prepareStatement("UPDATE execution_tasks SET status='EVIDENCE_PENDING',evidence_ref=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE workflow_id=? AND task_id=? AND status='LEASED' AND lease_token=? AND lease_until>?")) {
            p.setString(1,"artifact://communication/"+artifactRecordId);p.setString(2,java.time.Instant.now().toString());
            p.setString(3,workflowId);p.setString(4,taskId);p.setString(5,token);p.setString(6,java.time.Instant.now().toString());
            return p.executeUpdate()==1;
        }
    }
    @Override public void close() throws Exception {db.close();}
}
