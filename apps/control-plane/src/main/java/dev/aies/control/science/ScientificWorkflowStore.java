package dev.aies.control.science;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** P0.6 durable workflow journal and transactional outbox; single local SQLite owner. */
public final class ScientificWorkflowStore implements AutoCloseable {
    public record Event(String id, String workflowId, String type, String payloadJson, int attempts) {}
    public record Snapshot(String workflowId, String status, ScientificContracts.Plan plan,
                           String createdAt, String updatedAt) {}
    private final Connection db;
    private final ObjectMapper mapper;
    public ScientificWorkflowStore(Path file, ObjectMapper mapper) throws Exception {
        this.mapper = Objects.requireNonNull(mapper);
        Path path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Class.forName("org.sqlite.JDBC");
        db = DriverManager.getConnection("jdbc:sqlite:" + path);
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_workflows(workflow_id TEXT PRIMARY KEY,status TEXT NOT NULL,plan_json TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_outbox(id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,type TEXT NOT NULL,payload_json TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'PENDING',attempts INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS scientific_outbox_pending_idx ON scientific_outbox(status,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_inbox(consumer TEXT NOT NULL,event_id TEXT NOT NULL,processed_at TEXT NOT NULL,PRIMARY KEY(consumer,event_id))");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_evidence(id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,experiment_id TEXT NOT NULL,metric_value REAL NOT NULL,sample_size INTEGER NOT NULL,notes TEXT NOT NULL,created_at TEXT NOT NULL)");
        }
    }
    public synchronized Snapshot create(ScientificContracts.Plan raw) throws Exception {
        var plan = ScientificContracts.validate(raw);
        String now = Instant.now().toString();
        String json = mapper.writeValueAsString(plan);
        String eventId = UUID.randomUUID().toString();
        db.setAutoCommit(false);
        try {
            try (PreparedStatement ps = db.prepareStatement("INSERT INTO scientific_workflows VALUES(?,?,?,?,?)")) {
                ps.setString(1,plan.workflowId()); ps.setString(2,"PLANNED"); ps.setString(3,json);
                ps.setString(4,now); ps.setString(5,now); ps.executeUpdate();
            }
            enqueue(eventId,plan.workflowId(),"PLAN_CREATED", "{\"workflowId\":\""+plan.workflowId()+"\"}",now);
            db.commit();
        } catch(Exception e) {db.rollback(); throw e;}
        finally {db.setAutoCommit(true);}
        return new Snapshot(plan.workflowId(),"PLANNED",plan,now,now);
    }
    private void enqueue(String eventId,String workflowId,String type,String json,String now) throws Exception {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO scientific_outbox(id,workflow_id,type,payload_json,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1,eventId); ps.setString(2,workflowId); ps.setString(3,type);
            ps.setString(4,json); ps.setString(5,now); ps.executeUpdate();
        }
    }
    public synchronized Optional<Snapshot> find(String workflowId) throws Exception {
        validId(workflowId);
        try (PreparedStatement ps=db.prepareStatement("SELECT * FROM scientific_workflows WHERE workflow_id=?")) {
            ps.setString(1,workflowId);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) return Optional.empty();
                return Optional.of(new Snapshot(rs.getString("workflow_id"),rs.getString("status"),
                    mapper.readValue(rs.getString("plan_json"),ScientificContracts.Plan.class),
                    rs.getString("created_at"),rs.getString("updated_at")));
            }
        }
    }
    public synchronized List<Event> pending(int limit) throws Exception {
        if(limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid batch limit");
        var result=new ArrayList<Event>();
        try(PreparedStatement ps=db.prepareStatement("SELECT id,workflow_id,type,payload_json,attempts FROM scientific_outbox WHERE status='PENDING' ORDER BY created_at,id LIMIT ?")) {
            ps.setInt(1,limit);
            try(ResultSet rs=ps.executeQuery()) {while(rs.next()) result.add(new Event(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5)));}
        }
        return List.copyOf(result);
    }
    /** Atomic inbox + state transition + outbox acknowledgement; safe under replay. */
    public synchronized boolean acknowledge(String eventId, String consumer) throws Exception {
        validId(eventId);validId(consumer);
        db.setAutoCommit(false);
        try {
            try(PreparedStatement check=db.prepareStatement("SELECT 1 FROM scientific_outbox WHERE id=? AND status IN ('PENDING','DELIVERED')")) {
                check.setString(1,eventId);
                try(ResultSet rs=check.executeQuery()) {
                    if(!rs.next()) throw new IllegalArgumentException("Unknown event");
                }
            }
            int inserted;
            try(PreparedStatement ps=db.prepareStatement("INSERT OR IGNORE INTO scientific_inbox VALUES(?,?,?)")) {
                ps.setString(1,consumer);ps.setString(2,eventId);ps.setString(3,Instant.now().toString());
                inserted=ps.executeUpdate();
            }
            if(inserted == 1) {
                try(PreparedStatement ps=db.prepareStatement("UPDATE scientific_workflows SET status='READY_FOR_EXPERIMENT',updated_at=? WHERE workflow_id=(SELECT workflow_id FROM scientific_outbox WHERE id=? AND type='PLAN_CREATED') AND status='PLANNED'")) {
                    ps.setString(1,Instant.now().toString());ps.setString(2,eventId);ps.executeUpdate();
                }
            }
            try(PreparedStatement ps=db.prepareStatement("UPDATE scientific_outbox SET status='DELIVERED' WHERE id=?")) {
                ps.setString(1,eventId);ps.executeUpdate();
            }
            db.commit();return inserted==1;
        } catch(Exception e) {db.rollback();throw e;}
        finally {db.setAutoCommit(true);}
    }
    public synchronized void failed(String eventId, int maxAttempts) throws Exception {
        validId(eventId);
        if(maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("Invalid attempts");
        try(PreparedStatement ps=db.prepareStatement("UPDATE scientific_outbox SET attempts=attempts+1,status=CASE WHEN attempts+1>=? THEN 'DEAD' ELSE 'PENDING' END WHERE id=? AND status='PENDING'")) {
            ps.setInt(1,maxAttempts);ps.setString(2,eventId);ps.executeUpdate();
        }
    }
    public synchronized boolean recordEvidence(String workflowId,String experimentId,double metric,int sampleSize,String notes) throws Exception {
        validId(workflowId);validId(experimentId);
        if(!Double.isFinite(metric) || sampleSize < 1 || notes==null || notes.isBlank() || notes.length()>4096)
            throw new IllegalArgumentException("Invalid evidence");
        var snapshot=find(workflowId).orElseThrow();
        if(snapshot.plan().experiments().stream().noneMatch(e -> e.id().equals(experimentId)))
            throw new IllegalArgumentException("Unknown experiment");
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO scientific_evidence VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1,UUID.randomUUID().toString());ps.setString(2,workflowId);ps.setString(3,experimentId);
            ps.setDouble(4,metric);ps.setInt(5,sampleSize);ps.setString(6,notes);ps.setString(7,Instant.now().toString());
            return ps.executeUpdate()==1;
        }
    }
    public synchronized int evidenceCount(String workflowId) throws Exception {
        validId(workflowId);
        try(PreparedStatement ps=db.prepareStatement("SELECT count(*) FROM scientific_evidence WHERE workflow_id=?")) {
            ps.setString(1,workflowId);try(ResultSet rs=ps.executeQuery()) {return rs.next()?rs.getInt(1):0;}
        }
    }
    private static void validId(String id) {
        if(id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) throw new IllegalArgumentException("Invalid ID");
    }
    @Override public void close() throws Exception {db.close();}
}
