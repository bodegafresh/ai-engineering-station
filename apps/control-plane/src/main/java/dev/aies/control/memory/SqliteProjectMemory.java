package dev.aies.control.memory;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Persistent project-scoped lexical memory. No network, embeddings or automatic secret storage. */
public final class SqliteProjectMemory implements AutoCloseable {
    public record Entry(String id, String projectId, String kind, String content, Instant createdAt) {}
    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_-]{1,80}");
    private static final Pattern KIND = Pattern.compile("[a-z-]{1,40}");
    private final Connection db;
    private final int maxPerProject;

    public SqliteProjectMemory(Path path, int maxPerProject) throws SQLException {
        Objects.requireNonNull(path);
        if (maxPerProject < 1 || maxPerProject > 10000) throw new IllegalArgumentException("Invalid limit");
        this.maxPerProject = maxPerProject;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                "No se encontró el driver JDBC de SQLite en el classpath",
                e
            );
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA foreign_keys=ON");
            s.execute("CREATE TABLE IF NOT EXISTS project_memories (id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL, content TEXT NOT NULL, created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_project_memories_scope ON project_memories(project_id, created_at DESC, id DESC)");
            s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS project_memories_fts USING fts5(content, content='project_memories', content_rowid='rowid', tokenize='unicode61')");
            s.execute("CREATE TRIGGER IF NOT EXISTS pm_ai AFTER INSERT ON project_memories BEGIN INSERT INTO project_memories_fts(rowid, content) VALUES(new.rowid, new.content); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS pm_ad AFTER DELETE ON project_memories BEGIN INSERT INTO project_memories_fts(project_memories_fts, rowid, content) VALUES('delete', old.rowid, old.content); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS pm_au AFTER UPDATE ON project_memories BEGIN INSERT INTO project_memories_fts(project_memories_fts, rowid, content) VALUES('delete', old.rowid, old.content); INSERT INTO project_memories_fts(rowid, content) VALUES(new.rowid, new.content); END");
        } catch (SQLException | RuntimeException e) { try { db.close(); } catch (SQLException ignored) {} throw e; }
    }
    private static void checkId(String project) {
        if (project == null || !ID.matcher(project).matches()) throw new IllegalArgumentException("Invalid project ID");
    }
    public synchronized Entry remember(String project, String kind, String content) throws SQLException {
        checkId(project);
        if (kind == null || !KIND.matcher(kind).matches() || content == null || content.isBlank() || content.length() > 8192)
            throw new IllegalArgumentException("Invalid memory");
        Entry entry = new Entry(UUID.randomUUID().toString(), project, kind, content, Instant.now());
        boolean auto = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            try (PreparedStatement p = db.prepareStatement("INSERT INTO project_memories(id,project_id,kind,content,created_at) VALUES(?,?,?,?,?)")) {
                p.setString(1, entry.id()); p.setString(2, project); p.setString(3, kind);
                p.setString(4, content); p.setString(5, entry.createdAt().toString()); p.executeUpdate();
            }
            try (PreparedStatement p = db.prepareStatement("DELETE FROM project_memories WHERE id IN (SELECT id FROM project_memories WHERE project_id=? ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET ?)")) {
                p.setString(1, project); p.setInt(2, maxPerProject); p.executeUpdate();
            }
            db.commit();
            return entry;
        } catch (SQLException | RuntimeException e) { db.rollback(); throw e; }
        finally { db.setAutoCommit(auto); }
    }
    public synchronized List<Entry> retrieve(String project, String query, int limit) throws SQLException {
        checkId(project);
        if (query == null || query.isBlank() || query.length() > 512 || limit < 1 || limit > 50)
            throw new IllegalArgumentException("Invalid query");
        // Quote FTS terms; never interpolate untrusted query operators into SQL.
        List<String> tokens = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> t.length() > 2).distinct().limit(12).toList();
        if (tokens.isEmpty()) return List.of();
        String match = String.join(" OR ", tokens.stream().map(t -> "\"" + t.replace("\"", "\"\"") + "\"").toList());
        try (PreparedStatement p = db.prepareStatement("SELECT m.id,m.project_id,m.kind,m.content,m.created_at FROM project_memories m JOIN project_memories_fts f ON f.rowid=m.rowid WHERE m.project_id=? AND project_memories_fts MATCH ? ORDER BY bm25(project_memories_fts), m.created_at DESC LIMIT ?")) {
            p.setString(1, project); p.setString(2, match); p.setInt(3, limit);
            try (ResultSet r = p.executeQuery()) {
                List<Entry> found = new ArrayList<>();
                while (r.next()) found.add(new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),Instant.parse(r.getString(5))));
                return List.copyOf(found);
            }
        }
    }
    public synchronized int deleteProject(String project) throws SQLException {
        checkId(project);
        try (PreparedStatement p = db.prepareStatement("DELETE FROM project_memories WHERE project_id=?")) {
            p.setString(1, project); return p.executeUpdate();
        }
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
