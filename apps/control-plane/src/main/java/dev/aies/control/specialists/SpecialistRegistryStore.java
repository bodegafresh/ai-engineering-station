package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** S1-A isolated SQLite registry: no Event Bus wiring, activation, grants or model execution. */
public final class SpecialistRegistryStore implements AutoCloseable {
    public enum Status { DRAFT, VALIDATED, APPROVED, ACTIVE, REJECTED, DEPRECATED, REVOKED }
    public record Entry(String id,String version,Status status,long revision,String manifestSha256,String manifestJson,String origin) {}
    private final Connection db;
    private final ObjectMapper mapper;

    public SpecialistRegistryStore(Path file,ObjectMapper mapper) throws Exception {
        this.mapper=Objects.requireNonNull(mapper);
        Path path=Objects.requireNonNull(file).toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        db=new org.sqlite.JDBC().connect("jdbc:sqlite:"+path,new Properties());
        if(db==null)throw new SQLException("SQLite unavailable");
        try(var s=db.createStatement()){
            s.execute("PRAGMA busy_timeout=5000");s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS specialist_registry_profiles(id TEXT NOT NULL,version TEXT NOT NULL,status TEXT NOT NULL,revision INTEGER NOT NULL,manifest_sha256 TEXT NOT NULL,manifest_json TEXT NOT NULL,origin TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,PRIMARY KEY(id,version))");
            s.execute("CREATE INDEX IF NOT EXISTS specialist_registry_status_idx ON specialist_registry_profiles(status,id,version)");
        }
    }
    /** Untrusted proposals are always DRAFT; this method cannot publish. */
    public synchronized Entry propose(String json) throws Exception {
        var m=SpecialistRegistryManifest.validate(mapper,json);
        var now=Instant.now().toString();
        try(var p=db.prepareStatement("INSERT INTO specialist_registry_profiles VALUES(?,?,?,?,?,?,?,?,?)")){
            p.setString(1,m.id());p.setString(2,m.version());p.setString(3,Status.DRAFT.name());p.setLong(4,0);
            p.setString(5,m.sha256());p.setString(6,m.canonicalJson());p.setString(7,"PROPOSAL");p.setString(8,now);p.setString(9,now);
            p.executeUpdate();
        }
        return find(m.id(),m.version()).orElseThrow();
    }
    /** Trusted bootstrap only: caller must provide the frozen legacy metadata. Never called by model. */
    public synchronized int bootstrapLegacy() throws Exception {
        record Legacy(String id,String discipline,String capability1,String capability2,String role,int concurrency,int cost) {}
        var seeds=List.of(new Legacy("researcher","research","research",null,"RESEARCHER",2,1),
            new Legacy("requirements-auditor","quality","requirements","audit","AUDITOR",1,2),
            new Legacy("software-engineer","engineering.software","software","implementation","EXECUTOR",1,3),
            new Legacy("experiment-analyst","science","statistics","experiments","ANALYST",1,2));
        int inserted=0;
        try(var begin=db.createStatement()){begin.execute("BEGIN IMMEDIATE");}
        try{
            for(var seed:seeds){
                var obj=mapper.createObjectNode();obj.put("schemaVersion","2.0");obj.put("kind","SpecialistProfile");
                obj.put("id",seed.id());obj.put("version","1.0.0");obj.put("displayName",seed.id());obj.put("discipline",seed.discipline());
                var competencies=obj.putArray("competencies");
                for(String c:List.of(seed.capability1(),seed.capability2()==null?seed.capability1():seed.capability2())){
                    if(competencies.size()>0&&competencies.get(0).path("capabilityId").asText().equals(c))continue;
                    var x=competencies.addObject();x.put("capabilityId",c);x.put("capabilityVersion","1.0.0");x.put("requiredProficiency","FOUNDATIONAL");
                }
                obj.putArray("roles").add(seed.role());obj.putArray("requiredTools");
                obj.put("maxConcurrent",seed.concurrency());obj.put("costUnits",seed.cost());
                var m=SpecialistRegistryManifest.validate(mapper,mapper.writeValueAsString(obj));
                try(var p=db.prepareStatement("INSERT OR IGNORE INTO specialist_registry_profiles VALUES(?,?,?,?,?,?,?,?,?)")){
                    String now=Instant.now().toString();
                    p.setString(1,m.id());p.setString(2,m.version());p.setString(3,Status.ACTIVE.name());p.setLong(4,0);
                    p.setString(5,m.sha256());p.setString(6,m.canonicalJson());p.setString(7,"TRUSTED_LEGACY_BOOTSTRAP");p.setString(8,now);p.setString(9,now);
                    inserted+=p.executeUpdate();
                }
                Entry current=find(seed.id(),"1.0.0").orElseThrow();
                if(!current.manifestSha256().equals(m.sha256())||!current.origin().equals("TRUSTED_LEGACY_BOOTSTRAP")||current.status()!=Status.ACTIVE)
                    throw new SecurityException("Legacy seed collision");
            }
            try(var commit=db.createStatement()){commit.execute("COMMIT");}
            return inserted;
        }catch(Exception e){try(var rollback=db.createStatement()){rollback.execute("ROLLBACK");}throw e;}
    }
    /** Read verifies bytes against persisted digest on every access. No silent fallback. */
    public synchronized Optional<Entry> find(String id,String version) throws Exception {
        if(!id.matches("[a-z][a-z0-9.-]{1,127}")||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IllegalArgumentException("Invalid registry key");
        try(var p=db.prepareStatement("SELECT * FROM specialist_registry_profiles WHERE id=? AND version=?")){
            p.setString(1,id);p.setString(2,version);
            try(var rs=p.executeQuery()){
                if(!rs.next())return Optional.empty();
                String json=rs.getString("manifest_json");var verified=SpecialistRegistryManifest.validate(mapper,json);
                if(!verified.sha256().equals(rs.getString("manifest_sha256"))||!verified.id().equals(id)||!verified.version().equals(version)||!verified.canonicalJson().equals(json))
                    throw new SecurityException("Registry integrity violation");
                return Optional.of(new Entry(id,version,Status.valueOf(rs.getString("status")),rs.getLong("revision"),verified.sha256(),json,rs.getString("origin")));
            }
        }
    }
    /** Diagnostic read only. This registry is not yet a source of runtime assignments. */
    public synchronized List<Entry> list() throws Exception {
        var result=new ArrayList<Entry>();
        try(var p=db.prepareStatement("SELECT id,version FROM specialist_registry_profiles ORDER BY id,version");var rs=p.executeQuery()){
            while(rs.next())result.add(find(rs.getString(1),rs.getString(2)).orElseThrow());
        }
        return List.copyOf(result);
    }
    @Override public synchronized void close() throws Exception {db.close();}
}
