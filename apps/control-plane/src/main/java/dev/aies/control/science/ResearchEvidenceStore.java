package dev.aies.control.science;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** P0.10/P0.11: immutable provenance and experiment observations, backed by science.sqlite. */
public final class ResearchEvidenceStore implements AutoCloseable {
    public record SourceRecord(String id, ResearchEvidence.Source source, String createdAt) {}
    public record Observation(String id,String workflowId,String experimentId,boolean success,double cost,
                              double durationSeconds,String notes,String createdAt) {}
    private final Connection db;
    private final ObjectMapper mapper;
    public ResearchEvidenceStore(Path file,ObjectMapper mapper) throws Exception {
        this.mapper=Objects.requireNonNull(mapper);
        Path path=file.toAbsolutePath().normalize();Files.createDirectories(path.getParent());
        Class.forName("org.sqlite.JDBC");db=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(Statement s=db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_sources(id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,experiment_id TEXT NOT NULL,payload_json TEXT NOT NULL,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS scientific_sources_workflow_idx ON scientific_sources(workflow_id,created_at)");
            s.execute("CREATE TABLE IF NOT EXISTS scientific_observations(id TEXT PRIMARY KEY,workflow_id TEXT NOT NULL,experiment_id TEXT NOT NULL,success INTEGER NOT NULL,cost REAL NOT NULL,duration_seconds REAL NOT NULL,notes TEXT NOT NULL,created_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS scientific_observations_workflow_idx ON scientific_observations(workflow_id,experiment_id)");
        }
    }
    public SourceRecord addSource(ResearchEvidence.Source raw) throws Exception {
        var source=ResearchEvidence.validate(raw);ensureExperiment(source.workflowId(),source.experimentId());
        String id=UUID.randomUUID().toString(), now=Instant.now().toString();
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO scientific_sources VALUES(?,?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,source.workflowId());ps.setString(3,source.experimentId());
            ps.setString(4,mapper.writeValueAsString(source));ps.setString(5,now);ps.executeUpdate();
        }
        return new SourceRecord(id,source,now);
    }
    public List<SourceRecord> sources(String workflowId) throws Exception {
        ResearchEvidence.validId(workflowId);var out=new ArrayList<SourceRecord>();
        try(PreparedStatement ps=db.prepareStatement("SELECT id,payload_json,created_at FROM scientific_sources WHERE workflow_id=? ORDER BY created_at,id")) {
            ps.setString(1,workflowId);try(ResultSet rs=ps.executeQuery()) {while(rs.next())out.add(new SourceRecord(rs.getString(1),mapper.readValue(rs.getString(2),ResearchEvidence.Source.class),rs.getString(3)));}
        }
        return List.copyOf(out);
    }
    public Observation addObservation(String workflowId,String experimentId,boolean success,double cost,double durationSeconds,String notes) throws Exception {
        ResearchEvidence.validId(workflowId);ResearchEvidence.validId(experimentId);
        if(!Double.isFinite(cost)||cost<0||!Double.isFinite(durationSeconds)||durationSeconds<0)throw new IllegalArgumentException("Invalid measurement");
        ResearchEvidence.text(notes,4096);ensureExperiment(workflowId,experimentId);
        String id=UUID.randomUUID().toString(),now=Instant.now().toString();
        try(PreparedStatement ps=db.prepareStatement("INSERT INTO scientific_observations VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setString(1,id);ps.setString(2,workflowId);ps.setString(3,experimentId);ps.setInt(4,success?1:0);
            ps.setDouble(5,cost);ps.setDouble(6,durationSeconds);ps.setString(7,notes);ps.setString(8,now);ps.executeUpdate();
        }
        return new Observation(id,workflowId,experimentId,success,cost,durationSeconds,notes,now);
    }
    public List<Observation> observations(String workflowId) throws Exception {
        ResearchEvidence.validId(workflowId);var out=new ArrayList<Observation>();
        try(PreparedStatement ps=db.prepareStatement("SELECT * FROM scientific_observations WHERE workflow_id=? ORDER BY created_at,id")) {
            ps.setString(1,workflowId);try(ResultSet rs=ps.executeQuery()) {while(rs.next())out.add(new Observation(rs.getString("id"),rs.getString("workflow_id"),rs.getString("experiment_id"),rs.getInt("success")!=0,rs.getDouble("cost"),rs.getDouble("duration_seconds"),rs.getString("notes"),rs.getString("created_at")));}
        }
        return List.copyOf(out);
    }
    private void ensureExperiment(String workflowId,String experimentId) throws Exception {
        try(var store=new ScientificWorkflowStore(java.nio.file.Path.of(db.getMetaData().getURL().substring("jdbc:sqlite:".length())),mapper)) {
            var plan=store.find(workflowId).orElseThrow(()->new IllegalArgumentException("Unknown workflow"));
            if(plan.plan().experiments().stream().noneMatch(e->e.id().equals(experimentId)))throw new IllegalArgumentException("Unknown experiment");
        }
    }
    @Override public void close() throws Exception {db.close();}
}
