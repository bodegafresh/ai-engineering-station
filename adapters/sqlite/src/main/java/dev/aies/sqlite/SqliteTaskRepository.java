package dev.aies.sqlite;

import dev.aies.domain.Task;
import dev.aies.domain.TaskStatus;
import dev.aies.orchestration.TaskRepository;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Single-node durable workflow storage. JDBC calls MUST run off the Vert.x event loop. */
public final class SqliteTaskRepository implements TaskRepository {
    private final String jdbcUrl;
    public SqliteTaskRepository(String filePath) {
        jdbcUrl = "jdbc:sqlite:" + filePath;
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, objective TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)");
            // Additive migration from v0.1: keep existing tasks and timestamps.
            addColumn(c, "attempts", "INTEGER NOT NULL DEFAULT 0");
            addColumn(c, "lease_token", "TEXT");
            addColumn(c, "lease_until", "TEXT");
            addColumn(c, "last_error", "TEXT");
            addColumn(c, "model_response", "TEXT");
            addColumn(c, "model_name", "TEXT");
            s.execute("CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, event_type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL, delivered_at TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(delivered_at, id)");
        } catch (SQLException e) { throw new IllegalStateException("Cannot initialize SQLite", e); }
    }
    private static void addColumn(Connection c, String column, String declaration) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA table_info(tasks)")) {
            while (r.next()) if (column.equals(r.getString("name"))) return;
        }
        try (Statement s = c.createStatement()) { s.execute("ALTER TABLE tasks ADD COLUMN " + column + " " + declaration); }
    }
    private Connection open() throws SQLException {
        Connection c = new org.sqlite.JDBC()
            .connect(jdbcUrl, new java.util.Properties());

        if (c == null) {
            throw new SQLException("SQLite JDBC rejected URL: " + jdbcUrl);
        }
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private static Task read(ResultSet r) throws SQLException {
        return new Task(UUID.fromString(r.getString("id")), r.getString("objective"),
            TaskStatus.valueOf(r.getString("status")), Instant.parse(r.getString("created_at")),
            Instant.parse(r.getString("updated_at")));
    }
    private static void event(Connection c, String type, String payload) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("INSERT INTO outbox(event_type,payload,created_at) VALUES(?,?,?)")) {
            s.setString(1,type); s.setString(2,payload); s.setString(3,Instant.now().toString()); s.executeUpdate();
        }
    }
    @Override public Task create(String objective) {
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        Task task=new Task(id,objective,TaskStatus.PENDING,now,now);
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s=c.prepareStatement("INSERT INTO tasks(id,objective,status,created_at,updated_at) VALUES(?,?,?,?,?)")) {
                    s.setString(1,id.toString()); s.setString(2,objective); s.setString(3,"PENDING");
                    s.setString(4,now.toString()); s.setString(5,now.toString()); s.executeUpdate();
                }
                event(c,"task.created",id.toString()); c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Cannot create task",e); }
        return task;
    }
    @Override public Optional<Task> find(UUID id) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open(); PreparedStatement s=c.prepareStatement("SELECT * FROM tasks WHERE id=?")) {
            s.setString(1,id.toString());
            try (ResultSet r=s.executeQuery()) { return r.next()?Optional.of(read(r)):Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("Cannot read task",e); }
    }
    @Override public boolean cancel(UUID id) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c = open()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE tasks SET status='CANCELLED',updated_at=? WHERE id=? AND status='PENDING'")) {
                    s.setString(1, Instant.now().toString());
                    s.setString(2, id.toString());
                    updated = s.executeUpdate();
                }
                if (updated == 1) event(c, "task.cancelled", id.toString());
                c.commit();
                return updated == 1;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot cancel task", e);
        }
    }
    @Override public Optional<Claim> claimNext(Duration lease, int maxAttempts) {
        if (lease.isZero() || lease.isNegative() || maxAttempts<1) throw new IllegalArgumentException("Invalid claim policy");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                Task task=null; int attempts=0;
                try (PreparedStatement s=c.prepareStatement("SELECT * FROM tasks WHERE status='PENDING' AND attempts < ? ORDER BY created_at LIMIT 1")) {
                    s.setInt(1,maxAttempts);
                    try (ResultSet r=s.executeQuery()) { if(r.next()) { task=read(r); attempts=r.getInt("attempts"); } }
                }
                if(task==null) { c.commit(); return Optional.empty(); }
                String token=UUID.randomUUID().toString(); String now=Instant.now().toString();
                int changed;
                try (PreparedStatement s=c.prepareStatement("UPDATE tasks SET status='RUNNING',attempts=attempts+1,lease_token=?,lease_until=?,updated_at=? WHERE id=? AND status='PENDING'")) {
                    s.setString(1,token); s.setString(2,Instant.now().plus(lease).toString()); s.setString(3,now);
                    s.setString(4,task.id().toString()); changed=s.executeUpdate();
                }
                if(changed==1) event(c,"task.started",task.id().toString());
                c.commit();
                return changed==1?Optional.of(new Claim(find(task.id()).orElseThrow(),token,attempts+1)):Optional.empty();
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Cannot claim task",e); }
    }
    private boolean finish(Claim claim, String status, String reason) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement s=c.prepareStatement("UPDATE tasks SET status=?,updated_at=?,lease_token=NULL,lease_until=NULL,last_error=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?")) {
                    s.setString(1,status); s.setString(2,Instant.now().toString()); s.setString(3,reason);
                    s.setString(4,claim.task().id().toString()); s.setString(5,claim.leaseToken());
                    s.setString(6,Instant.now().toString()); updated=s.executeUpdate();
                }
                if(updated==1) event(c,"task."+status.toLowerCase(),claim.task().id().toString());
                c.commit(); return updated==1;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Cannot finish task",e); }
    }
    @Override public boolean completeWithResult(Claim claim, String result, String model) {
        if(result==null || result.isBlank() || model==null || model.isBlank())
            throw new IllegalArgumentException("A non-empty response and model are required");
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                int updated;
                String now=Instant.now().toString();
                try (PreparedStatement s=c.prepareStatement("UPDATE tasks SET status='COMPLETED',updated_at=?,lease_token=NULL,lease_until=NULL,last_error=NULL,model_response=?,model_name=? WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_until>?")) {
                    s.setString(1,now);s.setString(2,result);s.setString(3,model);
                    s.setString(4,claim.task().id().toString());s.setString(5,claim.leaseToken());s.setString(6,now);
                    updated=s.executeUpdate();
                }
                if(updated==1) event(c,"task.completed",claim.task().id().toString());
                c.commit();return updated==1;
            } catch (Exception e) { c.rollback();throw e; }
        } catch (Exception e) { throw new IllegalStateException("Cannot persist model response",e); }
    }
    @Override public Optional<TaskResult> findResult(UUID id) {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open();PreparedStatement s=c.prepareStatement("SELECT id,model_name,model_response,updated_at FROM tasks WHERE id=? AND status='COMPLETED' AND model_response IS NOT NULL")) {
            s.setString(1,id.toString());
            try(ResultSet r=s.executeQuery()) {
                return r.next()?Optional.of(new TaskResult(UUID.fromString(r.getString(1)),r.getString(2),r.getString(3),r.getString(4))):Optional.empty();
            }
        } catch(SQLException e) { throw new IllegalStateException("Cannot read model response",e); }
    }
    @Override public boolean complete(Claim claim) { return finish(claim,"COMPLETED",null); }
    @Override public boolean fail(Claim claim,String reason) { return finish(claim,"FAILED",reason); }
    @Override public int recoverExpired(int maxAttempts) {
        if(maxAttempts<1) throw new IllegalArgumentException("maxAttempts");
        String now=Instant.now().toString();
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                int recovered=0;
                try (PreparedStatement s=c.prepareStatement("SELECT id,attempts FROM tasks WHERE status='RUNNING' AND lease_until <= ?")) {
                    s.setString(1,now);
                    try (ResultSet r=s.executeQuery()) {
                        while(r.next()) {
                            String id=r.getString(1); boolean exhausted=r.getInt(2)>=maxAttempts;
                            try (PreparedStatement u=c.prepareStatement("UPDATE tasks SET status=?,lease_token=NULL,lease_until=NULL,updated_at=?,last_error=? WHERE id=? AND status='RUNNING'")) {
                                u.setString(1,exhausted?"FAILED":"PENDING");u.setString(2,now);
                                u.setString(3,exhausted?"Retry limit exceeded":"Lease expired; scheduled for retry");u.setString(4,id);
                                if(u.executeUpdate()==1) { recovered++; event(c,exhausted?"task.failed":"task.recovered",id); }
                            }
                        }
                    }
                }
                c.commit(); return recovered;
            } catch (Exception e) { c.rollback(); throw e; }
        } catch (Exception e) { throw new IllegalStateException("Cannot recover tasks",e); }
    }
    @Override public int publishOutbox(OutboxPublisher publisher,int limit) {
        if(limit<1) return 0;
        int published=0;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver is not available", e
            );
        }
        try (Connection c=open(); PreparedStatement s=c.prepareStatement("SELECT id,event_type,payload FROM outbox WHERE delivered_at IS NULL ORDER BY id LIMIT ?")) {
            s.setInt(1,limit);
            try (ResultSet r=s.executeQuery()) {
                while(r.next()) {
                    long id=r.getLong(1);
                    publisher.publish(r.getString(2),r.getString(3)); // may throw: remains undelivered
                    try (PreparedStatement u=c.prepareStatement("UPDATE outbox SET delivered_at=? WHERE id=? AND delivered_at IS NULL")) {
                        u.setString(1,Instant.now().toString());u.setLong(2,id);u.executeUpdate();
                    }
                    published++;
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Outbox dispatch failed",e); }
        return published;
    }
}
