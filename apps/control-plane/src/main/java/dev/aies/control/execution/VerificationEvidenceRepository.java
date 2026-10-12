package dev.aies.control.execution;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Append-only record of observed verification results. */
public final class VerificationEvidenceRepository implements AutoCloseable {
    private final Connection db;
    public VerificationEvidenceRepository(Path database) throws Exception {
        Path file=database.toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "SQLite JDBC driver no disponible en el classpath",
                e
            );
        }
        db=DriverManager.getConnection("jdbc:sqlite:"+file);
        try(Statement s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS verification_evidence("+
                    "id INTEGER PRIMARY KEY AUTOINCREMENT,proposal_id TEXT NOT NULL,"+
                    "status TEXT NOT NULL,profile TEXT NOT NULL,exit_code INTEGER NOT NULL,"+
                    "timed_out INTEGER NOT NULL,output_sha256 TEXT NOT NULL,"+
                    "output_excerpt TEXT NOT NULL,finished_at TEXT NOT NULL)");
        }
    }
    public synchronized void append(String id,VerificationEvidence evidence) throws SQLException {
        try(PreparedStatement ps=db.prepareStatement(
                "INSERT INTO verification_evidence(proposal_id,status,profile,exit_code,timed_out,output_sha256,output_excerpt,finished_at) VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,evidence.status());ps.setString(3,evidence.profile());
            ps.setInt(4,evidence.exitCode());ps.setInt(5,evidence.timedOut()?1:0);
            ps.setString(6,evidence.outputSha256());ps.setString(7,evidence.outputExcerpt());
            ps.setString(8,evidence.finishedAt().toString());ps.executeUpdate();
        }
    }
    public synchronized List<VerificationEvidence> history(String id) throws SQLException {
        List<VerificationEvidence> result=new ArrayList<>();
        try(PreparedStatement ps=db.prepareStatement(
                "SELECT * FROM verification_evidence WHERE proposal_id=? ORDER BY id")) {
            ps.setString(1,id);
            try(ResultSet rs=ps.executeQuery()) {
                while(rs.next()) result.add(new VerificationEvidence(
                        rs.getString("status"),rs.getString("profile"),rs.getInt("exit_code"),
                        rs.getInt("timed_out")!=0,rs.getString("output_sha256"),
                        rs.getString("output_excerpt"),java.time.Instant.parse(rs.getString("finished_at"))));
            }
        }
        return List.copyOf(result);
    }
    @Override public void close() throws SQLException {db.close();}
}
