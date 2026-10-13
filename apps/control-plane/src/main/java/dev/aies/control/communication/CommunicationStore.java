package dev.aies.control.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQLite-backed append-only records. Durable across application restarts. */
public final class CommunicationStore implements AutoCloseable {
    public record StoredRecord(String id, String workflowId, String taskId, String kind,
                               String schemaVersion, String sha256, String createdAt, Object payload) {}
    private final Connection db;
    private final ObjectMapper mapper;
    public CommunicationStore(Path database, ObjectMapper mapper) throws Exception {
        this.mapper=Objects.requireNonNull(mapper);
        Path file=database.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Class.forName("org.sqlite.JDBC");
        db=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(Statement s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS communication_records("+
                "id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,task_id TEXT NOT NULL,"+
                "kind TEXT NOT NULL,schema_version TEXT NOT NULL,sha256 TEXT NOT NULL,"+
                "payload_json TEXT NOT NULL,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS communication_workflow_idx ON communication_records(workflow_id,created_at,id)");
            // Unique, durable key owned by the destination database. Legacy append() remains unchanged.
            s.execute("CREATE TABLE IF NOT EXISTS communication_idempotency("+
                "operation_key TEXT PRIMARY KEY,record_id TEXT NOT NULL UNIQUE,"+
                "FOREIGN KEY(record_id) REFERENCES communication_records(id))");
        }
    }
    public synchronized StoredRecord append(String kind, String workflowId, String taskId, Object payload) throws Exception {
        CommunicationContracts.id(workflowId);CommunicationContracts.id(taskId);
        if(!Set.of("MESSAGE","ARTIFACT","AUDIT").contains(kind)) throw new IllegalArgumentException("Invalid record kind");
        String json=mapper.writeValueAsString(payload);
        String sha=sha256(json);
        String id=UUID.randomUUID().toString();
        String now=Instant.now().toString();
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO communication_records VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,workflowId);ps.setString(3,taskId);
            ps.setString(4,kind);ps.setString(5,CommunicationContracts.VERSION);
            ps.setString(6,sha);ps.setString(7,json);ps.setString(8,now);
            ps.executeUpdate();
        }
        return new StoredRecord(id,workflowId,taskId,kind,CommunicationContracts.VERSION,sha,now,payload);
    }
    /** One record per logical operation. Conflicting replays are rejected, never silently accepted. */
    public synchronized StoredRecord appendIdempotent(String operationKey, String kind,
                                                       String workflowId, String taskId, Object payload) throws Exception {
        CommunicationContracts.id(operationKey);
        CommunicationContracts.id(workflowId);
        CommunicationContracts.id(taskId);
        if (!Set.of("MESSAGE", "ARTIFACT", "AUDIT").contains(kind))
            throw new IllegalArgumentException("Invalid record kind");
        String json = mapper.writeValueAsString(payload);
        String sha = sha256(json);
        db.setAutoCommit(false);
        try {
            // BEGIN DEFERRED is sufficient with the unique constraint; concurrent writers serialize in SQLite.
            try (var p = db.prepareStatement("SELECT r.* FROM communication_idempotency i "+
                    "JOIN communication_records r ON r.id=i.record_id WHERE i.operation_key=?")) {
                p.setString(1, operationKey);
                try (var rs = p.executeQuery()) {
                    if (rs.next()) {
                        if (!kind.equals(rs.getString("kind")) || !workflowId.equals(rs.getString("workflow_id"))
                                || !taskId.equals(rs.getString("task_id")) || !sha.equals(rs.getString("sha256")))
                            throw new SecurityException("Conflicting idempotent operation");
                        var existing = read(rs);
                        db.commit();
                        return existing;
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            String now = Instant.now().toString();
            try (var p = db.prepareStatement("INSERT INTO communication_records VALUES(?,?,?,?,?,?,?,?)")) {
                p.setString(1,id);p.setString(2,workflowId);p.setString(3,taskId);
                p.setString(4,kind);p.setString(5,CommunicationContracts.VERSION);
                p.setString(6,sha);p.setString(7,json);p.setString(8,now);p.executeUpdate();
            }
            try (var p = db.prepareStatement("INSERT INTO communication_idempotency(operation_key,record_id) VALUES(?,?)")) {
                p.setString(1,operationKey);p.setString(2,id);p.executeUpdate();
            }
            db.commit();
            return new StoredRecord(id,workflowId,taskId,kind,CommunicationContracts.VERSION,sha,now,payload);
        } catch (Exception e) {db.rollback();throw e;}
        finally {db.setAutoCommit(true);}
    }
    public synchronized List<StoredRecord> list(String workflowId) throws Exception {
        CommunicationContracts.id(workflowId);
        List<StoredRecord> records=new ArrayList<>();
        try(PreparedStatement ps=db.prepareStatement(
                "SELECT * FROM communication_records WHERE workflow_id=? ORDER BY created_at,id")) {
            ps.setString(1,workflowId);
            try(ResultSet rs=ps.executeQuery()) {
                while(rs.next()) records.add(read(rs));
            }
        }
        return List.copyOf(records);
    }
    public synchronized Optional<StoredRecord> find(String id) throws Exception {
        CommunicationContracts.id(id);
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM communication_records WHERE id=?")) {
            ps.setString(1,id);
            try(ResultSet rs=ps.executeQuery()) {
                return rs.next()?Optional.of(read(rs)):Optional.empty();
            }
        }
    }
    private StoredRecord read(ResultSet rs) throws Exception {
        String kind=rs.getString("kind");
        Class<?> type=switch(kind) {
            case "MESSAGE" -> CommunicationContracts.Message.class;
            case "ARTIFACT" -> CommunicationContracts.Artifact.class;
            case "AUDIT" -> CommunicationContracts.Audit.class;
            default -> throw new IllegalStateException("Unknown persisted record kind");
        };
        String json=rs.getString("payload_json");
        if(!sha256(json).equals(rs.getString("sha256"))) throw new SecurityException("Record checksum mismatch");
        return new StoredRecord(rs.getString("id"),rs.getString("workflow_id"),rs.getString("task_id"),
                kind,rs.getString("schema_version"),rs.getString("sha256"),rs.getString("created_at"),
                mapper.readValue(json,type));
    }
    private static String sha256(String value) throws Exception {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(bytes);
    }
    @Override public void close() throws Exception {db.close();}
}
