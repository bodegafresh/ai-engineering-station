package dev.aies.control.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable, append-only lineage. Revisions create a new workflow; rejected parents are never edited. */
public final class PlanningRevisionStore implements AutoCloseable {
    public record Request(String requestId, String expectedParentAuditId, PlanningContracts.Intake intake) {}
    public record Result(String requestId, String parentWorkflowId, String parentAuditId,
                         String workflowId, String status, int depth, boolean replayed) {}
    private final Connection db;
    private final ObjectMapper mapper;
    public PlanningRevisionStore(Path path, ObjectMapper mapper) throws Exception {
        this.mapper=Objects.requireNonNull(mapper);
        Path file=path.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");
        db=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(var s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS planning_revisions("
                +"request_id TEXT PRIMARY KEY,parent_workflow_id TEXT NOT NULL,"
                +"parent_audit_id TEXT NOT NULL,workflow_id TEXT NOT NULL UNIQUE,"
                +"intake_sha256 TEXT NOT NULL,depth INTEGER NOT NULL,"
                +"created_at TEXT NOT NULL)");
        }
    }
    public synchronized Result revise(String parent, Request request) throws Exception {
        PlanningContracts.id(parent);
        Objects.requireNonNull(request);
        PlanningContracts.id(request.requestId());
        PlanningContracts.id(request.expectedParentAuditId());
        var intake=PlanningContracts.validate(request.intake());
        if(parent.equals(intake.workflowId()))throw new IllegalArgumentException("Revision needs a new workflow ID");
        String intakeJson=mapper.writeValueAsString(intake);
        String hash=sha(intakeJson);
        db.setAutoCommit(false);
        try {
            try(var p=db.prepareStatement("SELECT parent_workflow_id,parent_audit_id,workflow_id,intake_sha256,depth FROM planning_revisions WHERE request_id=?")){
                p.setString(1,request.requestId());
                try(var rs=p.executeQuery()){
                    if(rs.next()){
                        if(!parent.equals(rs.getString(1)) || !request.expectedParentAuditId().equals(rs.getString(2))
                            || !intake.workflowId().equals(rs.getString(3)) || !hash.equals(rs.getString(4)))
                            throw new IllegalStateException("Idempotency key reused with different request");
                        var result=new Result(request.requestId(),parent,request.expectedParentAuditId(),
                            intake.workflowId(),"RECEIVED",rs.getInt(5),true);
                        db.commit();return result;
                    }
                }
            }
            String parentIntake;
            try(var p=db.prepareStatement("SELECT status,audit_id,intake_json FROM planning_workflows WHERE workflow_id=?")){
                p.setString(1,parent);
                try(var rs=p.executeQuery()){
                    if(!rs.next() || !"REVISION_REQUIRED".equals(rs.getString(1))
                        || !request.expectedParentAuditId().equals(rs.getString(2)))
                        throw new IllegalStateException("Parent must be revision-required with exact audit ID");
                    parentIntake=rs.getString(3);
                }
            }
            var old=mapper.readValue(parentIntake,PlanningContracts.Intake.class);
            var comparableOld=mapper.valueToTree(old);
            var comparableNew=mapper.valueToTree(intake);
            ((com.fasterxml.jackson.databind.node.ObjectNode)comparableOld).remove("workflowId");
            ((com.fasterxml.jackson.databind.node.ObjectNode)comparableNew).remove("workflowId");
            if(comparableOld.equals(comparableNew))
                throw new IllegalArgumentException("Revision must change intake content");
            int depth=1;
            try(var p=db.prepareStatement("SELECT depth FROM planning_revisions WHERE workflow_id=?")){
                p.setString(1,parent);
                try(var rs=p.executeQuery()){if(rs.next())depth=rs.getInt(1)+1;}
            }
            if(depth>3)throw new IllegalStateException("Revision depth limit reached");
            String now=Instant.now().toString();
            try(var p=db.prepareStatement("INSERT INTO planning_workflows(workflow_id,status,intake_json,plan_json,artifact_id,audit_id,created_at,updated_at) VALUES(?, 'RECEIVED', ?, NULL,NULL,NULL,?,?)")){
                p.setString(1,intake.workflowId());p.setString(2,intakeJson);p.setString(3,now);p.setString(4,now);p.executeUpdate();
            }
            try(var p=db.prepareStatement("INSERT INTO planning_outbox(id,workflow_id,status,attempts,created_at) VALUES(?,?,'PENDING',0,?)")){
                p.setString(1,UUID.randomUUID().toString());p.setString(2,intake.workflowId());p.setString(3,now);p.executeUpdate();
            }
            try(var p=db.prepareStatement("INSERT INTO planning_revisions VALUES(?,?,?,?,?,?,?)")){
                p.setString(1,request.requestId());p.setString(2,parent);
                p.setString(3,request.expectedParentAuditId());p.setString(4,intake.workflowId());
                p.setString(5,hash);p.setInt(6,depth);p.setString(7,now);p.executeUpdate();
            }
            db.commit();
            return new Result(request.requestId(),parent,request.expectedParentAuditId(),intake.workflowId(),"RECEIVED",depth,false);
        }catch(Exception e){db.rollback();throw e;}
        finally{db.setAutoCommit(true);}
    }
    public synchronized Optional<Result> find(String requestId)throws Exception{
        PlanningContracts.id(requestId);
        try(var p=db.prepareStatement("SELECT parent_workflow_id,parent_audit_id,workflow_id,depth FROM planning_revisions WHERE request_id=?")){
            p.setString(1,requestId);
            try(var rs=p.executeQuery()){
                if(!rs.next())return Optional.empty();
                return Optional.of(new Result(requestId,rs.getString(1),rs.getString(2),rs.getString(3),"RECEIVED",rs.getInt(4),true));
            }
        }
    }
    private static String sha(String value)throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(bytes);
    }
    @Override public void close()throws Exception{db.close();}
}
