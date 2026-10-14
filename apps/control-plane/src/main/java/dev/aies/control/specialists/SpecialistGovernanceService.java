package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * S1-B isolated governance service. No REST/Event Bus wiring or implicit authorization.
 * Caller identity and scopes must be established by a trusted operator boundary.
 */
public final class SpecialistGovernanceService implements AutoCloseable {
    public enum Action { VALIDATE, APPROVE, ACTIVATE, REJECT, DEPRECATE, REVOKE }
    public record Principal(String subject, Set<String> scopes) {
        public Principal {
            if (subject == null || !subject.matches("[a-zA-Z0-9._@:-]{3,128}"))
                throw new IllegalArgumentException("Invalid principal");
            scopes = Set.copyOf(Objects.requireNonNull(scopes));
        }
    }
    public record Decision(String id, String version, String state, long revision,
                           String manifestSha256, String actor, String action) {}
    private final Connection db;
    private final ObjectMapper mapper;
    // Package-private deterministic crash seam. Never configurable through REST or LLM.
    private final Runnable beforeCommitProbe;

    public SpecialistGovernanceService(Path file, ObjectMapper mapper) throws Exception {
        this(file, mapper, () -> {});
    }

    SpecialistGovernanceService(Path file, ObjectMapper mapper, Runnable beforeCommitProbe) throws Exception {
        this.beforeCommitProbe = Objects.requireNonNull(beforeCommitProbe);
        this.mapper = Objects.requireNonNull(mapper);
        db = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath().normalize(),
                                           new java.util.Properties());
        if (db == null) throw new SQLException("SQLite unavailable");
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS specialist_registry_decisions (" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, version TEXT NOT NULL," +
                "from_status TEXT NOT NULL, to_status TEXT NOT NULL, from_revision INTEGER NOT NULL," +
                "to_revision INTEGER NOT NULL, manifest_sha256 TEXT NOT NULL, actor TEXT NOT NULL," +
                "action TEXT NOT NULL, created_at TEXT NOT NULL," +
                "UNIQUE(id,version,to_revision))");
            s.execute("CREATE INDEX IF NOT EXISTS specialist_registry_decisions_lookup " +
                "ON specialist_registry_decisions(id,version,seq)");
            s.execute("CREATE TABLE IF NOT EXISTS specialist_governance_idempotency (" +
                "command_key TEXT PRIMARY KEY, id TEXT NOT NULL, version TEXT NOT NULL," +
                "action TEXT NOT NULL, expected_revision INTEGER NOT NULL," +
                "expected_hash TEXT NOT NULL, actor TEXT NOT NULL," +
                "state TEXT NOT NULL, result_revision INTEGER NOT NULL)");
        }
    }

    public synchronized Decision transition(String id, String version, Action action,
                                            long expectedRevision, String expectedHash,
                                            Principal principal) throws Exception {
        return transition(id,version,action,expectedRevision,expectedHash,principal,null);
    }

    /** Durable optional command key; original overload remains compatible. */
    public synchronized Decision transition(String id, String version, Action action,
                                            long expectedRevision, String expectedHash,
                                            Principal principal, String commandKey) throws Exception {
        if (commandKey != null && !commandKey.matches("[a-zA-Z0-9._:-]{8,128}"))
            throw new IllegalArgumentException("Invalid command key");
        Objects.requireNonNull(action);
        Objects.requireNonNull(principal);
        if (!principal.scopes().contains("specialists:" + action.name().toLowerCase(java.util.Locale.ROOT)))
            throw new SecurityException("Specialist governance permission denied");
        if (expectedRevision < 0 || expectedHash == null || !expectedHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid precondition");
        // Validate key and manifest using the same strict S1-A verifier before mutation.
        try (var registry = new SpecialistRegistryStore(dbPath(), mapper)) {
            var entry = registry.find(id, version).orElseThrow(
                () -> new IllegalArgumentException("Unknown specialist revision"));
            if (!entry.manifestSha256().equals(expectedHash))
                throw new SecurityException("Manifest digest mismatch");
        }
        boolean originalAutoCommit = db.getAutoCommit();
        db.setAutoCommit(false);
        try {
            if (commandKey != null) {
                try (PreparedStatement p=db.prepareStatement(
                    "SELECT id,version,action,expected_revision,expected_hash,actor,state,result_revision " +
                    "FROM specialist_governance_idempotency WHERE command_key=?")) {
                    p.setString(1,commandKey);
                    try(ResultSet rs=p.executeQuery()) {
                        if(rs.next()) {
                            if (!rs.getString(1).equals(id) || !rs.getString(2).equals(version)
                                || !rs.getString(3).equals(action.name())
                                || rs.getLong(4)!=expectedRevision
                                || !rs.getString(5).equals(expectedHash)
                                || !rs.getString(6).equals(principal.subject()))
                                throw new SecurityException("Idempotency key conflict");
                            Decision prior=new Decision(id,version,rs.getString(7),rs.getLong(8),
                                expectedHash,principal.subject(),action.name());
                            db.commit();
                            return prior;
                        }
                    }
                }
            }
            String before;
            long revision;
            String hash;
            String origin;
            try (PreparedStatement p = db.prepareStatement(
                    "SELECT status,revision,manifest_sha256,origin FROM specialist_registry_profiles " +
                    "WHERE id=? AND version=?")) {
                p.setString(1,id); p.setString(2,version);
                try (ResultSet rs=p.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Specialist disappeared");
                    before=rs.getString(1); revision=rs.getLong(2);
                    hash=rs.getString(3); origin=rs.getString(4);
                }
            }
            if (!hash.equals(expectedHash)) throw new SecurityException("Manifest digest mismatch");
            if (revision != expectedRevision) throw new SQLException("Stale specialist revision");
            if ("TRUSTED_LEGACY_BOOTSTRAP".equals(origin))
                throw new SecurityException("Legacy bootstrap entries are immutable");
            String after = target(before, action);
            long next = Math.addExact(revision, 1);
            try (PreparedStatement p = db.prepareStatement(
                    "UPDATE specialist_registry_profiles SET status=?,revision=?,updated_at=? " +
                    "WHERE id=? AND version=? AND status=? AND revision=? AND manifest_sha256=?")) {
                p.setString(1,after); p.setLong(2,next); p.setString(3,Instant.now().toString());
                p.setString(4,id);p.setString(5,version);p.setString(6,before);
                p.setLong(7,revision);p.setString(8,expectedHash);
                if (p.executeUpdate()!=1) throw new SQLException("Concurrent governance update");
            }
            try (PreparedStatement p=db.prepareStatement(
                    "INSERT INTO specialist_registry_decisions " +
                    "(id,version,from_status,to_status,from_revision,to_revision,manifest_sha256,actor,action,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                p.setString(1,id);p.setString(2,version);p.setString(3,before);
                p.setString(4,after);p.setLong(5,revision);p.setLong(6,next);
                p.setString(7,hash);p.setString(8,principal.subject());
                p.setString(9,action.name());p.setString(10,Instant.now().toString());
                p.executeUpdate();
            }
            if (commandKey != null) {
                try (PreparedStatement p=db.prepareStatement(
                    "INSERT INTO specialist_governance_idempotency " +
                    "(command_key,id,version,action,expected_revision,expected_hash,actor,state,result_revision) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,commandKey);p.setString(2,id);p.setString(3,version);
                    p.setString(4,action.name());p.setLong(5,expectedRevision);
                    p.setString(6,expectedHash);p.setString(7,principal.subject());
                    p.setString(8,after);p.setLong(9,next);p.executeUpdate();
                }
            }
            // Invoked after all writes, while the transaction is still open.
            // Test worker may terminate its JVM here to prove atomic rollback.
            beforeCommitProbe.run();
            db.commit();
            return new Decision(id,version,after,next,hash,principal.subject(),action.name());
        } catch (Exception e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(originalAutoCommit);
        }
    }

    private Path dbPath() throws SQLException {
        try (Statement s=db.createStatement(); ResultSet rs=s.executeQuery("PRAGMA database_list")) {
            if (!rs.next()) throw new SQLException("Missing SQLite database");
            return Path.of(rs.getString("file"));
        }
    }

    private static String target(String from, Action action) {
        return switch (action) {
            case VALIDATE -> require(from, "DRAFT", "VALIDATED");
            case APPROVE -> require(from, "VALIDATED", "APPROVED");
            case ACTIVATE -> require(from, "APPROVED", "ACTIVE");
            case REJECT -> {
                if (!from.equals("DRAFT") && !from.equals("VALIDATED"))
                    throw new IllegalStateException("Invalid rejection transition");
                yield "REJECTED";
            }
            case DEPRECATE -> require(from, "ACTIVE", "DEPRECATED");
            case REVOKE -> {
                if (!from.equals("APPROVED") && !from.equals("ACTIVE") && !from.equals("DEPRECATED"))
                    throw new IllegalStateException("Invalid revocation transition");
                yield "REVOKED";
            }
        };
    }
    private static String require(String actual,String expected,String next) {
        if (!actual.equals(expected)) throw new IllegalStateException("Invalid specialist transition");
        return next;
    }
    @Override public synchronized void close() throws SQLException { db.close(); }
}
