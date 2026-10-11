package dev.aies.sqlite;

import dev.aies.domain.Task;
import dev.aies.domain.TaskStatus;
import dev.aies.orchestration.TaskRepository;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable single-process SQLite task store; SQLite is the source of truth, not the Event Bus. */
public final class SqliteTaskRepository implements TaskRepository {
    private final String url;
    public SqliteTaskRepository(String filePath) {
        url = "jdbc:sqlite:" + filePath;
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, objective TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)");
            addColumnIfAbsent(c, "attempts", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfAbsent(c, "lease_until", "TEXT");
            addColumnIfAbsent(c, "next_run_at", "TEXT");
            addColumnIfAbsent(c, "last_error", "TEXT");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tasks_ready ON tasks(status,next_run_at,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS task_outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, event_type TEXT NOT NULL, created_at TEXT NOT NULL, published_at TEXT)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_outbox_pending ON task_outbox(published_at,id)");
        } catch (SQLException e) { throw new IllegalStateException("Cannot initialize SQLite", e); }
    }
    private static void addColumnIfAbsent(Connection c, String name, String ddl) throws SQLException {
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info(tasks)")) {
            while (r.next()) if (name.equals(r.getString("name"))) return;
        }
        try (Statement s=c.createStatement()) { s.execute("ALTER TABLE tasks ADD COLUMN " + name + " " + ddl); }
    }
    private Connection open() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s=c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private static Task read(ResultSet r) throws SQLException {
        return new Task(UUID.fromString(r.getString("id")),r.getString("objective"),TaskStatus.valueOf(r.getString("status")),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));
    }
    private static void outbox(Connection c, UUID id, String type) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT INTO task_outbox(task_id,event_type,created_at) VALUES(?,?,?)")) {
            s.setString(1,id.toString());s.setString(2,type);s.setString(3,Instant.now().toString());s.executeUpdate();
        }
    }
    @Override public Task create(String objective) {
        UUID id=UUID.randomUUID();Instant now=Instant.now();
        Task task=new Task(id,objective,TaskStatus.PENDING,now,now);
        try (Connection c=open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s=c.prepareStatement("INSERT INTO tasks(id,objective,status,created_at,updated_at,next_run_at) VALUES(?,?,?,?,?,?)")) {
                    s.setString(1,id.toString());s.setString(2,objective);s.setString(3,"PENDING");s.setString(4,now.toString());s.setString(5,now.toString());s.setString(6,now.toString());s.executeUpdate();
                }
                outbox(c,id,"task.created");c.commit();
            } catch (SQLException e) { c.rollback();throw e; }
        } catch (SQLException e) { throw new IllegalStateException("Cannot create task",e); }
        return task;
    }
    @Override public Optional<Task> find(UUID id) {
        try (Connection c=open();PreparedStatement s=c.prepareStatement("SELECT * FROM tasks WHERE id=?")) {
            s.setString(1,id.toString());try (ResultSet r=s.executeQuery()) {return r.next()?Optional.of(read(r)):Optional.empty();}
        } catch(SQLException e){throw new IllegalStateException("Cannot read task",e);}
    }
    @Override public boolean cancel(UUID id) {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                int changed;
                try(PreparedStatement s=c.prepareStatement("UPDATE tasks SET status='CANCELLED',lease_until=NULL,updated_at=? WHERE id=? AND status='PENDING'")) {
                    s.setString(1,Instant.now().toString());s.setString(2,id.toString());changed=s.executeUpdate();
                }
                if(changed==1)outbox(c,id,"task.cancelled");c.commit();return changed==1;
            } catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new IllegalStateException("Cannot cancel task",e);}
    }
    @Override public Optional<Task> claimNext(int leaseSeconds) {
        if(leaseSeconds<1)throw new IllegalArgumentException("leaseSeconds must be positive");
        Instant now=Instant.now();
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                // SQLite permits only one writer; conditional UPDATE prevents duplicate claims.
                String id=null;
                try(PreparedStatement s=c.prepareStatement("SELECT id FROM tasks WHERE status='PENDING' AND (next_run_at IS NULL OR next_run_at<=?) ORDER BY created_at LIMIT 1")) {
                    s.setString(1,now.toString());try(ResultSet r=s.executeQuery()){if(r.next())id=r.getString(1);}
                }
                if(id==null){c.commit();return Optional.empty();}
                try(PreparedStatement s=c.prepareStatement("UPDATE tasks SET status='RUNNING',attempts=attempts+1,lease_until=?,updated_at=? WHERE id=? AND status='PENDING'")) {
                    s.setString(1,now.plusSeconds(leaseSeconds).toString());s.setString(2,now.toString());s.setString(3,id);
                    if(s.executeUpdate()!=1){c.commit();return Optional.empty();}
                }
                outbox(c,UUID.fromString(id),"task.running");
                Task task;
                try(PreparedStatement s=c.prepareStatement("SELECT * FROM tasks WHERE id=?")) {
                    s.setString(1,id);try(ResultSet r=s.executeQuery()){r.next();task=read(r);}
                }
                c.commit();return Optional.of(task);
            }catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new IllegalStateException("Cannot claim task",e);}
    }
    @Override public boolean complete(UUID id) { return transition(id,"COMPLETED",null,null); }
    private boolean transition(UUID id,String status,String reason,Instant nextRun) {
        try(Connection c=open()) {
            c.setAutoCommit(false);
            try {
                int changed;
                try(PreparedStatement s=c.prepareStatement("UPDATE tasks SET status=?,last_error=?,next_run_at=?,lease_until=NULL,updated_at=? WHERE id=? AND status='RUNNING'")) {
                    s.setString(1,status);s.setString(2,reason);s.setString(3,nextRun==null?null:nextRun.toString());s.setString(4,Instant.now().toString());s.setString(5,id.toString());changed=s.executeUpdate();
                }
                if(changed==1)outbox(c,id,"task."+status.toLowerCase());c.commit();return changed==1;
            }catch(SQLException e){c.rollback();throw e;}
        }catch(SQLException e){throw new IllegalStateException("Cannot transition task",e);}
    }
    @Override public boolean failOrRetry(UUID id,String reason,int maxAttempts,int backoffSeconds) {
        if(maxAttempts<1||backoffSeconds<0)throw new IllegalArgumentException("Invalid retry policy");
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT attempts FROM tasks WHERE id=? AND status='RUNNING'")) {
            s.setString(1,id.toString());try(ResultSet r=s.executeQuery()) {
                if(!r.next())return false;
                boolean retry=r.getInt(1)<maxAttempts;
                return transition(id,retry?"PENDING":"FAILED",reason,retry?Instant.now().plusSeconds(backoffSeconds):null);
            }
        }catch(SQLException e){throw new IllegalStateException("Cannot retry task",e);}
    }
    @Override public int recoverExpired() {
        Instant now=Instant.now();List<UUID> expired=new ArrayList<>();
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT id FROM tasks WHERE status='RUNNING' AND lease_until<=?")) {
            s.setString(1,now.toString());try(ResultSet r=s.executeQuery()){while(r.next())expired.add(UUID.fromString(r.getString(1)));}
        }catch(SQLException e){throw new IllegalStateException("Cannot find expired leases",e);}
        int count=0;
        for(UUID id:expired) {
            try(Connection c=open()) {
                c.setAutoCommit(false);
                try {
                    int changed;
                    try(PreparedStatement s=c.prepareStatement("UPDATE tasks SET status='PENDING',lease_until=NULL,next_run_at=?,updated_at=? WHERE id=? AND status='RUNNING' AND lease_until<=?")) {
                        s.setString(1,now.toString());s.setString(2,now.toString());s.setString(3,id.toString());s.setString(4,now.toString());changed=s.executeUpdate();
                    }
                    if(changed==1){outbox(c,id,"task.recovered");count++;}c.commit();
                }catch(SQLException e){c.rollback();throw e;}
            }catch(SQLException e){throw new IllegalStateException("Cannot recover task",e);}
        }
        return count;
    }
    @Override public List<OutboxEvent> pendingEvents(int limit) {
        List<OutboxEvent> result=new ArrayList<>();
        try(Connection c=open();PreparedStatement s=c.prepareStatement("SELECT id,task_id,event_type FROM task_outbox WHERE published_at IS NULL ORDER BY id LIMIT ?")) {
            s.setInt(1,limit);try(ResultSet r=s.executeQuery()) {while(r.next())result.add(new OutboxEvent(r.getLong(1),UUID.fromString(r.getString(2)),r.getString(3)));}
        }catch(SQLException e){throw new IllegalStateException("Cannot read outbox",e);}
        return result;
    }
    @Override public void acknowledgeEvent(long eventId) {
        try(Connection c=open();PreparedStatement s=c.prepareStatement("UPDATE task_outbox SET published_at=? WHERE id=? AND published_at IS NULL")) {
            s.setString(1,Instant.now().toString());s.setLong(2,eventId);s.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("Cannot acknowledge outbox",e);}
    }
}
