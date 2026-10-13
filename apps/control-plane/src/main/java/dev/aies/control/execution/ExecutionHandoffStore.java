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
            s.execute("CREATE TABLE IF NOT EXISTS execution_attempt_effects(workflow_id TEXT NOT NULL,task_id TEXT NOT NULL,attempt_id TEXT NOT NULL,phase TEXT NOT NULL,artifact_id TEXT,document_path TEXT,document_sha256 TEXT,updated_at TEXT NOT NULL,PRIMARY KEY(workflow_id,task_id,attempt_id))");
            s.execute("CREATE TABLE IF NOT EXISTS execution_capability_authorizations(workflow_id TEXT NOT NULL, task_id TEXT NOT NULL, capability TEXT NOT NULL, source_path TEXT NOT NULL, source_sha256 TEXT NOT NULL, created_at TEXT NOT NULL, PRIMARY KEY(workflow_id,task_id), FOREIGN KEY(workflow_id,task_id) REFERENCES execution_tasks(workflow_id,task_id))");
            // Additive migration for existing databases. Audit identity survives restarts.
            boolean hasAuditColumn = false;
            try (var columns = db.createStatement().executeQuery("PRAGMA table_info(execution_tasks)")) {
                while (columns.next()) if ("audit_record_id".equals(columns.getString("name"))) hasAuditColumn = true;
            }
            if (!hasAuditColumn) s.execute("ALTER TABLE execution_tasks ADD COLUMN audit_record_id TEXT");
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
    /** Durable, immutable operator authorization for one task. Replays with different inputs fail closed. */
    public synchronized boolean authorizeCapability(ExecutionCapabilityAuthorization a) throws Exception {
        Objects.requireNonNull(a);
        try (var p=db.prepareStatement("SELECT specialist_id,status FROM execution_tasks WHERE workflow_id=? AND task_id=?")) {
            p.setString(1,a.workflowId());p.setString(2,a.taskId());
            try(var rs=p.executeQuery()) {
                if(!rs.next()) throw new IllegalArgumentException("Unknown execution task");
                if(!"software-engineer".equals(rs.getString(1)) || !"PENDING".equals(rs.getString(2)))
                    throw new IllegalStateException("Task is not authorizable");
            }
        }
        try (var p=db.prepareStatement("INSERT OR IGNORE INTO execution_capability_authorizations(workflow_id,task_id,capability,source_path,source_sha256,created_at) VALUES(?,?,?,?,?,?)")) {
            p.setString(1,a.workflowId());p.setString(2,a.taskId());p.setString(3,a.capability());
            p.setString(4,a.sourcePath());p.setString(5,a.sourceSha256());p.setString(6,Instant.now().toString());
            if(p.executeUpdate()==1) return true;
        }
        if(!authorization(a.workflowId(),a.taskId()).orElseThrow().equals(a))
            throw new IllegalStateException("Conflicting authorization");
        return false;
    }
    public synchronized Optional<ExecutionCapabilityAuthorization> authorization(String workflowId,String taskId) throws Exception {
        dev.aies.control.communication.CommunicationContracts.id(workflowId);
        dev.aies.control.communication.CommunicationContracts.id(taskId);
        try(var p=db.prepareStatement("SELECT capability,source_path,source_sha256 FROM execution_capability_authorizations WHERE workflow_id=? AND task_id=?")) {
            p.setString(1,workflowId);p.setString(2,taskId);
            try(var rs=p.executeQuery()) {
                if(!rs.next()) return Optional.empty();
                return Optional.of(new ExecutionCapabilityAuthorization(workflowId,taskId,rs.getString(1),rs.getString(2),rs.getString(3)));
            }
        }
    }
    /** Check both durable operator grant and current unexpired lease. No trust in Event Bus flags. */
    public synchronized ExecutionCapabilityAuthorization requireActiveDocumentation(String workflowId,String taskId,String attemptId,String sourcePath,String sourceSha256) throws Exception {
        ExecutionDispatchPolicy.token(attemptId);
        var grant=authorization(workflowId,taskId).orElseThrow(()->new SecurityException("No durable authorization"));
        if(!"workspace-documentation".equals(grant.capability()) || !grant.sourcePath().equals(sourcePath)
            || !grant.sourceSha256().equals(sourceSha256)) throw new SecurityException("Authorization mismatch");
        try(var p=db.prepareStatement("SELECT 1 FROM execution_tasks WHERE workflow_id=? AND task_id=? AND status='LEASED' AND lease_token=? AND lease_until>?")) {
            p.setString(1,workflowId);p.setString(2,taskId);p.setString(3,attemptId);p.setString(4,Instant.now().toString());
            try(var rs=p.executeQuery()) {if(!rs.next())throw new SecurityException("Stale or missing lease");}
        }
        return grant;
    }
    /** Durable per-attempt effect state. Never overwrites a different artifact for the same attempt. */
    public synchronized void recordEffect(String workflowId,String taskId,String attemptId,String phase,String artifactId,String documentPath,String documentSha256) throws Exception {
        if(!Set.of("PREPARED","FILE_WRITTEN","ARTIFACT_RECORDED").contains(phase))throw new IllegalArgumentException("Invalid phase");
        requireActiveDocumentation(workflowId,taskId,attemptId,
            authorization(workflowId,taskId).orElseThrow().sourcePath(),
            authorization(workflowId,taskId).orElseThrow().sourceSha256());
        // A phase is monotonic: PREPARED -> FILE_WRITTEN -> ARTIFACT_RECORDED.
        // Repeating the same phase with the same evidence is a no-op.
        var current=effect(workflowId,taskId,attemptId);
        if(current.isPresent()) {
            var previous=current.get();
            if(previous.phase().equals(phase)) {
                if(!Objects.equals(previous.artifactId(),artifactId) ||
                   !Objects.equals(previous.documentPath(),documentPath) ||
                   !Objects.equals(previous.documentSha256(),documentSha256))
                    throw new SecurityException("Conflicting effect replay");
                return;
            }
            boolean next=("PREPARED".equals(previous.phase()) && "FILE_WRITTEN".equals(phase)) ||
                         ("FILE_WRITTEN".equals(previous.phase()) && "ARTIFACT_RECORDED".equals(phase));
            if(!next)throw new IllegalStateException("Invalid effect phase transition");
            if("ARTIFACT_RECORDED".equals(phase) &&
               (!Objects.equals(previous.documentPath(),documentPath) ||
                !Objects.equals(previous.documentSha256(),documentSha256)))
                throw new SecurityException("Material evidence changed during transition");
            try(var p=db.prepareStatement("UPDATE execution_attempt_effects SET phase=?,artifact_id=?,document_path=?,document_sha256=?,updated_at=? WHERE workflow_id=? AND task_id=? AND attempt_id=? AND phase=?")) {
                p.setString(1,phase);p.setString(2,artifactId);p.setString(3,documentPath);
                p.setString(4,documentSha256);p.setString(5,Instant.now().toString());
                p.setString(6,workflowId);p.setString(7,taskId);p.setString(8,attemptId);p.setString(9,previous.phase());
                if(p.executeUpdate()!=1)throw new IllegalStateException("Concurrent effect transition");
            }
        } else {
            if(!"PREPARED".equals(phase) || artifactId!=null || documentPath!=null || documentSha256!=null)
                throw new IllegalStateException("Effect must begin at PREPARED");
            try(var p=db.prepareStatement("INSERT INTO execution_attempt_effects(workflow_id,task_id,attempt_id,phase,artifact_id,document_path,document_sha256,updated_at) VALUES(?,?,?,?,?,?,?,?)")) {
                p.setString(1,workflowId);p.setString(2,taskId);p.setString(3,attemptId);p.setString(4,phase);
                p.setString(5,artifactId);p.setString(6,documentPath);p.setString(7,documentSha256);p.setString(8,Instant.now().toString());
                p.executeUpdate();
            }
        }
    }
    public record Effect(String phase,String artifactId,String documentPath,String documentSha256) {}
    public synchronized Optional<Effect> effect(String workflowId,String taskId,String attemptId) throws Exception {
        try(var p=db.prepareStatement("SELECT phase,artifact_id,document_path,document_sha256 FROM execution_attempt_effects WHERE workflow_id=? AND task_id=? AND attempt_id=?")) {
            p.setString(1,workflowId);p.setString(2,taskId);p.setString(3,attemptId);
            try(var rs=p.executeQuery()) {return rs.next()?Optional.of(new Effect(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4))):Optional.empty();}
        }
    }
    /** Legacy direct completion is disabled: all work must pass verified evidence and audit. */
    @Deprecated(forRemoval = true)
    public synchronized boolean complete(String workflowId,String taskId,String token,String evidenceRef) {
        throw new UnsupportedOperationException("Direct completion bypasses evidence audit; use approveEvidence");
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
    /** Complete only the exact staged artifact after independently verified audit approval. */
    public synchronized boolean approveEvidence(String workflowId, String taskId,
                                                  String evidenceRef, String auditRecordId) throws Exception {
        dev.aies.control.communication.CommunicationContracts.id(workflowId);
        dev.aies.control.communication.CommunicationContracts.id(taskId);
        dev.aies.control.communication.CommunicationContracts.id(auditRecordId);
        if (evidenceRef == null || !evidenceRef.startsWith("artifact://communication/"))
            throw new IllegalArgumentException("Invalid evidence reference");
        try (var p = db.prepareStatement("UPDATE execution_tasks SET status='COMPLETED',audit_record_id=?,updated_at=? WHERE workflow_id=? AND task_id=? AND status='EVIDENCE_PENDING' AND evidence_ref=?")) {
            p.setString(1,auditRecordId);p.setString(2,java.time.Instant.now().toString());
            p.setString(3,workflowId);p.setString(4,taskId);p.setString(5,evidenceRef);
            return p.executeUpdate()==1;
        }
    }
    /** Durable approval provenance for final-delivery manifest. */
    public synchronized Optional<String> approvedAuditId(String workflowId, String taskId) throws Exception {
        dev.aies.control.communication.CommunicationContracts.id(workflowId);
        dev.aies.control.communication.CommunicationContracts.id(taskId);
        try (var p = db.prepareStatement("SELECT audit_record_id FROM execution_tasks WHERE workflow_id=? AND task_id=? AND status='COMPLETED'")) {
            p.setString(1,workflowId);p.setString(2,taskId);
            try (var rs=p.executeQuery()) { return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(); }
        }
    }
    @Override public void close() throws Exception {db.close();}
}
