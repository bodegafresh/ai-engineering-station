package dev.aies.sqlite;
import dev.aies.domain.Task;
import dev.aies.domain.TaskStatus;
import dev.aies.orchestration.TaskRepository;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Single-process SQLite repository for the first POC; no multi-node claims. */
public final class SqliteTaskRepository implements TaskRepository {
    private final String jdbcUrl;
    public SqliteTaskRepository(String filePath) {
        this.jdbcUrl = "jdbc:sqlite:" + filePath;
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, objective TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)");
        } catch (SQLException e) { throw new IllegalStateException("Cannot initialize SQLite", e); }
    }
    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(jdbcUrl);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    @Override public Task create(String objective) {
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        Task task = new Task(id, objective, TaskStatus.PENDING, now, now);
        try (Connection c = open(); PreparedStatement s = c.prepareStatement("INSERT INTO tasks VALUES(?,?,?,?,?)")) {
            s.setString(1,id.toString()); s.setString(2,objective); s.setString(3,"PENDING");
            s.setString(4,now.toString()); s.setString(5,now.toString()); s.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("Cannot create task", e); }
        return task;
    }
    @Override public Optional<Task> find(UUID id) {
        try (Connection c = open(); PreparedStatement s = c.prepareStatement("SELECT * FROM tasks WHERE id=?")) {
            s.setString(1,id.toString());
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                return Optional.of(new Task(UUID.fromString(r.getString("id")),r.getString("objective"),TaskStatus.valueOf(r.getString("status")),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at"))));
            }
        } catch (SQLException e) { throw new IllegalStateException("Cannot read task", e); }
    }
    @Override public boolean markCompleted(UUID id) {
        try (Connection c = open(); PreparedStatement s = c.prepareStatement("UPDATE tasks SET status='COMPLETED',updated_at=? WHERE id=? AND status='PENDING'")) {
            s.setString(1,Instant.now().toString()); s.setString(2,id.toString()); return s.executeUpdate()==1;
        } catch (SQLException e) { throw new IllegalStateException("Cannot complete task", e); }
    }
    @Override public int processPending(int limit) {
        int done=0;
        try (Connection c = open(); PreparedStatement s = c.prepareStatement("SELECT id FROM tasks WHERE status='PENDING' ORDER BY created_at LIMIT ?")) {
            s.setInt(1,limit);
            try (ResultSet r=s.executeQuery()) { while(r.next()) if(markCompleted(UUID.fromString(r.getString(1)))) done++; }
        } catch (SQLException e) { throw new IllegalStateException("Cannot reconcile tasks",e); }
        return done;
    }
}
