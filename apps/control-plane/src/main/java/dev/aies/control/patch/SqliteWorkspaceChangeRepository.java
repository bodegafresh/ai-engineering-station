package dev.aies.control.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Durable SQLite proposal journal. State transitions use conditional updates. */
public final class SqliteWorkspaceChangeRepository implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    public SqliteWorkspaceChangeRepository(Path database) throws Exception {
        Path path=database.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        connection=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(Statement s=connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS workspace_change (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, state TEXT NOT NULL, reviewer TEXT, fingerprint TEXT NOT NULL, changes_json TEXT NOT NULL, created_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS workspace_change_event (id INTEGER PRIMARY KEY AUTOINCREMENT, proposal_id TEXT NOT NULL, state TEXT NOT NULL, actor TEXT, recorded_at TEXT NOT NULL)");
        }
        // A crash during an APPLYING operation cannot be assumed to have completed.
        try(Statement s=connection.createStatement()) {
            s.executeUpdate("UPDATE workspace_change SET state='RECOVERY_REQUIRED' WHERE state='APPLYING'");
        }
    }
    public synchronized void create(WorkspaceChangeService.Proposal p) throws Exception {
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO workspace_change VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1,p.id());ps.setString(2,p.projectId());ps.setString(3,p.status());
            ps.setString(4,p.reviewer());ps.setString(5,p.approvalSha256());
            ps.setString(6,mapper.writeValueAsString(p.changes()));ps.setString(7,p.createdAt().toString());
            ps.executeUpdate();
        }
        event(p.id(),p.status(),p.reviewer());
    }
    public synchronized WorkspaceChangeService.Proposal find(String id) throws Exception {
        try(PreparedStatement ps=connection.prepareStatement("SELECT * FROM workspace_change WHERE id=?")) {
            ps.setString(1,id);
            try(ResultSet rs=ps.executeQuery()) {
                if(!rs.next()) throw new NoSuchElementException("Unknown proposal");
                List<WorkspaceChangeService.Change> changes =
                    mapper.readValue(
                            rs.getString("changes_json"),
                            mapper.getTypeFactory().constructCollectionType(
                                    List.class,
                                    WorkspaceChangeService.Change.class
                            )
                    );
                return new WorkspaceChangeService.Proposal(rs.getString("id"),rs.getString("project_id"),
                        rs.getString("state"),rs.getString("reviewer"),rs.getString("fingerprint"),
                        List.copyOf(changes),Instant.parse(rs.getString("created_at")));
            }
        }
    }
    public synchronized boolean transition(String id,String from,String to,String reviewer) throws Exception {
        try(PreparedStatement ps=connection.prepareStatement(
                "UPDATE workspace_change SET state=?,reviewer=? WHERE id=? AND state=?")) {
            ps.setString(1,to);ps.setString(2,reviewer);ps.setString(3,id);ps.setString(4,from);
            if(ps.executeUpdate()!=1) return false;
        }
        event(id,to,reviewer);
        return true;
    }
    private void event(String id,String state,String actor) throws SQLException {
        try(PreparedStatement ps=connection.prepareStatement(
                "INSERT INTO workspace_change_event(proposal_id,state,actor,recorded_at) VALUES(?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,state);ps.setString(3,actor);
            ps.setString(4,Instant.now().toString());ps.executeUpdate();
        }
    }
    @Override public synchronized void close() throws SQLException {connection.close();}
}
