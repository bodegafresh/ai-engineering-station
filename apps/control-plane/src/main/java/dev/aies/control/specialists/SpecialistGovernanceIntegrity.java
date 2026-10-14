package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;

/** Read-only fail-closed continuity verification of S1-B governance history. */
public final class SpecialistGovernanceIntegrity {
    private SpecialistGovernanceIntegrity() {}

    /** Does not prove tamper resistance against an attacker who can rewrite the entire SQLite file. */
    public static void verify(Path file, ObjectMapper mapper) throws Exception {
        Objects.requireNonNull(file);
        Objects.requireNonNull(mapper);
        try (var store = new SpecialistRegistryStore(file, mapper);
             Connection db = new org.sqlite.JDBC().connect(
                 "jdbc:sqlite:" + file.toAbsolutePath().normalize(), new java.util.Properties())) {
            if (db == null) throw new SQLException("SQLite unavailable");
            try (Statement statement = db.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT id,version,status,revision,manifest_sha256,origin " +
                     "FROM specialist_registry_profiles ORDER BY id,version")) {
                while (rows.next()) {
                    String id = rows.getString("id"), version = rows.getString("version");
                    var entry = store.find(id, version).orElseThrow();
                    long revision = entry.revision();
                    if (revision < 0) throw new SecurityException("Negative governance revision");
                    if (!entry.status().name().equals(rows.getString("status")) ||
                        !entry.manifestSha256().equals(rows.getString("manifest_sha256")))
                        throw new SecurityException("Registry snapshot mismatch");
                    boolean legacy = "TRUSTED_LEGACY_BOOTSTRAP".equals(rows.getString("origin"));
                    if (legacy && (revision != 0 || entry.status() != SpecialistRegistryStore.Status.ACTIVE))
                        throw new SecurityException("Invalid legacy state");
                    if (!legacy && !"PROPOSAL".equals(rows.getString("origin")))
                        throw new SecurityException("Unknown specialist origin");
                    long expected = 0;
                    String state = legacy ? "ACTIVE" : "DRAFT";
                    try (PreparedStatement p = db.prepareStatement(
                        "SELECT from_status,to_status,from_revision,to_revision,manifest_sha256,actor,action " +
                        "FROM specialist_registry_decisions WHERE id=? AND version=? ORDER BY to_revision")) {
                        p.setString(1, id); p.setString(2, version);
                        try (ResultSet decisions = p.executeQuery()) {
                            while (decisions.next()) {
                                if (legacy || decisions.getLong("from_revision") != expected ||
                                    decisions.getLong("to_revision") != expected + 1 ||
                                    !state.equals(decisions.getString("from_status")) ||
                                    !entry.manifestSha256().equals(decisions.getString("manifest_sha256")) ||
                                    decisions.getString("actor") == null ||
                                    decisions.getString("actor").isBlank())
                                    throw new SecurityException("Governance history discontinuity");
                                String action = decisions.getString("action");
                                String next = decisions.getString("to_status");
                                if (!allowed(state, action, next))
                                    throw new SecurityException("Invalid governance history transition");
                                state = next;
                                expected++;
                            }
                        }
                    }
                    if (expected != revision || !state.equals(entry.status().name()))
                        throw new SecurityException("Governance history does not match registry");
                }
            }
            // Detect orphan decisions (including decisions for deleted profiles).
            try (Statement s = db.createStatement(); ResultSet r = s.executeQuery(
                "SELECT count(*) FROM specialist_registry_decisions d " +
                "LEFT JOIN specialist_registry_profiles p ON p.id=d.id AND p.version=d.version " +
                "WHERE p.id IS NULL")) {
                if (r.next() && r.getLong(1) != 0)
                    throw new SecurityException("Orphan governance decisions");
            }
        }
    }

    private static boolean allowed(String from, String action, String to) {
        if (action == null || to == null) return false;
        return switch (action) {
            case "VALIDATE" -> from.equals("DRAFT") && to.equals("VALIDATED");
            case "APPROVE" -> from.equals("VALIDATED") && to.equals("APPROVED");
            case "ACTIVATE" -> from.equals("APPROVED") && to.equals("ACTIVE");
            case "REJECT" -> (from.equals("DRAFT") || from.equals("VALIDATED")) && to.equals("REJECTED");
            case "DEPRECATE" -> from.equals("ACTIVE") && to.equals("DEPRECATED");
            case "REVOKE" -> (from.equals("APPROVED") || from.equals("ACTIVE") || from.equals("DEPRECATED")) && to.equals("REVOKED");
            default -> false;
        };
    }
}
