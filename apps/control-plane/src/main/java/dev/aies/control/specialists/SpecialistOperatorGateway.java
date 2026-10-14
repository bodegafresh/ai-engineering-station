package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/**
 * S1-C.2 trusted, opt-in operator boundary. Not an HTTP endpoint.
 * Credential bindings must come from operator-controlled configuration, never a model request.
 */
public final class SpecialistOperatorGateway implements AutoCloseable {
    /** Only SHA-256 digests are stored here; raw credentials are not retained. */
    public record Operator(String subject, String credentialSha256, Set<String> scopes) {
        public Operator {
            new SpecialistGovernanceService.Principal(subject, scopes);
            if (credentialSha256 == null || !credentialSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid credential digest");
            scopes = Set.copyOf(scopes);
            if (scopes.isEmpty() || scopes.stream().anyMatch(s -> !s.matches("specialists:(validate|approve|activate|reject|deprecate|revoke)")))
                throw new IllegalArgumentException("Unsupported governance scope");
        }
    }

    private final SpecialistGovernanceService governance;
    private final List<Operator> operators;
    private final Path dbPath;
    private final ObjectMapper mapper;

    public SpecialistOperatorGateway(Path file, ObjectMapper mapper, Collection<Operator> configured) throws Exception {
        this.dbPath = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper);
        this.operators = List.copyOf(Objects.requireNonNull(configured));
        if (operators.isEmpty()) throw new SecurityException("No configured operators");
        var subjects = new HashSet<String>();
        var digests = new HashSet<String>();
        for (var operator : operators) {
            if (!subjects.add(operator.subject()) || !digests.add(operator.credentialSha256()))
                throw new SecurityException("Ambiguous operator identity");
        }
        // Initialize the governance schema before verifying its decision history.
        // S1-C.1 queries specialist_registry_decisions, which does not exist on
        // a freshly proposed S1-A registry until S1-B initializes it.
        SpecialistGovernanceService opened = new SpecialistGovernanceService(dbPath, mapper);
        try {
            // Fail closed on corruption, and never leak an opened connection.
            SpecialistGovernanceIntegrity.verify(dbPath, mapper);
            installSeparationGuard();
        } catch (Exception | Error failure) {
            try { opened.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
        this.governance = opened;
    }

    /** Deterministic credential hashing helper for offline operator provisioning. */
    public static String sha256(String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Weak credential");
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private Operator authenticate(String supplied) {
        byte[] suppliedDigest = new byte[32];
        if (supplied != null && supplied.length() >= 32 && supplied.length() <= 4096) {
            try { suppliedDigest = HexFormat.of().parseHex(sha256(supplied)); }
            catch (IllegalArgumentException ignored) { /* deny */ }
        }
        Operator match = null;
        for (var operator : operators) {
            byte[] configured = HexFormat.of().parseHex(operator.credentialSha256());
            if (MessageDigest.isEqual(suppliedDigest, configured)) match = operator;
        }
        if (match == null) throw new SecurityException("Operator authentication denied");
        return match;
    }

    public synchronized SpecialistGovernanceService.Decision transition(
            String credential, String id, String version, SpecialistGovernanceService.Action action,
            long expectedRevision, String expectedHash) throws Exception {
        Objects.requireNonNull(action);
        Operator operator = authenticate(credential);
        String scope = "specialists:" + action.name().toLowerCase(Locale.ROOT);
        if (!operator.scopes().contains(scope)) throw new SecurityException("Operator scope denied");
        // Verify the durable chain before any mutation. The SQLite trigger below enforces
        // approval/activation separation in the same transaction as the decision insert.
        SpecialistGovernanceIntegrity.verify(dbPath, mapper);
        return governance.transition(id, version, action, expectedRevision, expectedHash,
                new SpecialistGovernanceService.Principal(operator.subject(), operator.scopes()));
    }

    private void installSeparationGuard() throws Exception {
        try (Connection db = new org.sqlite.JDBC().connect("jdbc:sqlite:" + dbPath, new Properties());
             Statement st = db.createStatement()) {
            // This is intentionally a DB-level invariant, not a preflight-only check.
            st.execute("CREATE TRIGGER IF NOT EXISTS specialist_governance_distinct_activator " +
                "BEFORE INSERT ON specialist_registry_decisions " +
                "WHEN NEW.action='ACTIVATE' AND EXISTS (" +
                "SELECT 1 FROM specialist_registry_decisions d " +
                "WHERE d.id=NEW.id AND d.version=NEW.version AND d.action='APPROVE' " +
                "AND d.to_revision=NEW.from_revision AND d.actor=NEW.actor) " +
                "BEGIN SELECT RAISE(ABORT, 'approver cannot activate'); END");
        }
    }

    @Override public void close() throws Exception { governance.close(); }
}
