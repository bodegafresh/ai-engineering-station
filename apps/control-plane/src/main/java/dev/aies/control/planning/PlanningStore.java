package dev.aies.control.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable intake, plan and outbox; no partial plan writes. */
public final class PlanningStore implements AutoCloseable {
 private final Connection db;
 private final ObjectMapper mapper;
 public record Snapshot(String workflowId,String status,PlanningContracts.Intake intake,
                        PlanningContracts.Plan plan,String artifactRecordId,String auditRecordId,
                        String createdAt,String updatedAt) {}
 public record Pending(String id,String workflowId,int attempts) {}
 public PlanningStore(Path file,ObjectMapper mapper) throws Exception {
  this.mapper=Objects.requireNonNull(mapper);
  Path path=file.toAbsolutePath().normalize();Files.createDirectories(path.getParent());
  Class.forName("org.sqlite.JDBC");db=DriverManager.getConnection("jdbc:sqlite:"+path);
  try(var s=db.createStatement()){
   s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");
   s.execute("CREATE TABLE IF NOT EXISTS planning_workflows(workflow_id TEXT PRIMARY KEY,status TEXT NOT NULL,intake_json TEXT NOT NULL,plan_json TEXT,artifact_id TEXT,audit_id TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
   s.execute("CREATE TABLE IF NOT EXISTS planning_outbox(id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,created_at TEXT NOT NULL)");
   s.execute("CREATE TABLE IF NOT EXISTS planning_inbox(event_id TEXT PRIMARY KEY,processed_at TEXT NOT NULL)");
  }
 }
 public synchronized Snapshot create(PlanningContracts.Intake raw) throws Exception {
  var in=PlanningContracts.validate(raw);
  var now=Instant.now().toString();
  db.setAutoCommit(false);
  try {
   try(var p=db.prepareStatement("INSERT INTO planning_workflows VALUES(?,?,?,?,?,?,?,?)")){
    p.setString(1,in.workflowId());p.setString(2,"RECEIVED");p.setString(3,mapper.writeValueAsString(in));
    p.setNull(4,Types.VARCHAR);p.setNull(5,Types.VARCHAR);p.setNull(6,Types.VARCHAR);
    p.setString(7,now);p.setString(8,now);p.executeUpdate();
   }
   try(var p=db.prepareStatement("INSERT INTO planning_outbox VALUES(?,?,?,?,?)")){
    p.setString(1,UUID.randomUUID().toString());p.setString(2,in.workflowId());
    p.setString(3,"PENDING");p.setInt(4,0);p.setString(5,now);p.executeUpdate();
   }
   db.commit();
  }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
  return find(in.workflowId()).orElseThrow();
 }
 public synchronized Optional<Snapshot> find(String id) throws Exception {
  PlanningContracts.id(id);
  try(var p=db.prepareStatement("SELECT * FROM planning_workflows WHERE workflow_id=?")){
   p.setString(1,id);
   try(var rs=p.executeQuery()){
    if(!rs.next())return Optional.empty();
    var plan=rs.getString("plan_json");
    return Optional.of(new Snapshot(id,rs.getString("status"),
      mapper.readValue(rs.getString("intake_json"),PlanningContracts.Intake.class),
      plan==null?null:mapper.readValue(plan,PlanningContracts.Plan.class),
      rs.getString("artifact_id"),rs.getString("audit_id"),
      rs.getString("created_at"),rs.getString("updated_at")));
   }
  }
 }
 public synchronized List<Pending> pending(int limit) throws Exception {
  var result=new ArrayList<Pending>();
  try(var p=db.prepareStatement("SELECT id,workflow_id,attempts FROM planning_outbox WHERE status='PENDING' ORDER BY created_at,id LIMIT ?")){
   p.setInt(1,limit);
   try(var rs=p.executeQuery()) {while(rs.next())result.add(new Pending(rs.getString(1),rs.getString(2),rs.getInt(3)));}
  }
  return List.copyOf(result);
 }
 public synchronized boolean complete(String eventId,PlanningContracts.Plan plan) throws Exception {
  db.setAutoCommit(false);
  try{
   int n;
   try(var p=db.prepareStatement("INSERT OR IGNORE INTO planning_inbox VALUES(?,?)")){
    p.setString(1,eventId);p.setString(2,Instant.now().toString());n=p.executeUpdate();
   }
   if(n==1){
    try(var p=db.prepareStatement("UPDATE planning_workflows SET plan_json=?,status=?,updated_at=? WHERE workflow_id=? AND status='RECEIVED'")){
     p.setString(1,mapper.writeValueAsString(plan));p.setString(2,plan.status());
     p.setString(3,Instant.now().toString());p.setString(4,plan.workflowId());
     if(p.executeUpdate()!=1)throw new IllegalStateException("Invalid state");
    }
   }
   try(var p=db.prepareStatement("UPDATE planning_outbox SET status='DELIVERED' WHERE id=?")){
    p.setString(1,eventId);p.executeUpdate();
   }
   db.commit();return n==1;
  }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
 }
 public synchronized void failed(String eventId) throws Exception {
  try(var p=db.prepareStatement("UPDATE planning_outbox SET attempts=attempts+1,status=CASE WHEN attempts>=4 THEN 'DEAD' ELSE 'PENDING' END WHERE id=? AND status='PENDING'")){
   p.setString(1,eventId);p.executeUpdate();
  }
 }
 public synchronized boolean attachArtifact(String id,String artifact) throws Exception {
  try(var p=db.prepareStatement("UPDATE planning_workflows SET artifact_id=?,status='AWAITING_AUDIT',updated_at=? WHERE workflow_id=? AND status='AUDIT_REQUIRED' AND artifact_id IS NULL")){
   p.setString(1,artifact);p.setString(2,Instant.now().toString());p.setString(3,id);return p.executeUpdate()==1;
  }
 }
 public synchronized boolean attachAudit(String id,String audit,String status) throws Exception {
  if(!Set.of("READY_FOR_ASSIGNMENT","REVISION_REQUIRED","BLOCKED").contains(status))throw new IllegalArgumentException("Invalid status");
  try(var p=db.prepareStatement("UPDATE planning_workflows SET audit_id=?,status=?,updated_at=? WHERE workflow_id=? AND status='AWAITING_AUDIT'")){
   p.setString(1,audit);p.setString(2,status);p.setString(3,Instant.now().toString());p.setString(4,id);return p.executeUpdate()==1;
  }
 }
 @Override public void close()throws Exception{db.close();}
}
