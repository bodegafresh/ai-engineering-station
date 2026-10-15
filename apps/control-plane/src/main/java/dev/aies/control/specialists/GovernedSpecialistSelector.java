package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.sqlite.SQLiteConfig;

/** H02-C: isolated, opt-in, read-only selection from verified ACTIVE registry records.
 * Does not expose REST, grant tools, or replace Event Bus v1.
 */
public final class GovernedSpecialistSelector {
    private final Path database;
    private final ObjectMapper mapper;
    private final boolean enabled;

    public GovernedSpecialistSelector(Path database, ObjectMapper mapper, boolean enabled) {
        this.database = Objects.requireNonNull(database).toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper);
        this.enabled = enabled;
    }

    /** H02-D2C2: verify a specific assignment at point of use; never choose a replacement.
     * No tool grants exist in the worker path, so tool-requiring profiles are ineligible.
     */
    public boolean eligibleForDispatch(String specialistId, String capability) throws Exception {
        if (!enabled) throw new SecurityException("Governed selection disabled");
        // Read the entire registry with the same integrity checks as selection, but do not
        // select the cheapest candidate: the previously assigned identity must still match.
        return eligibleCandidates().stream().anyMatch(candidate ->
            candidate.id().equals(specialistId)
                && candidate.capabilities().contains(capability)
                && candidate.requiredTools().isEmpty());
    }

    /** Disabled mode never touches SQLite. Enabled mode fails closed on any integrity or I/O failure. */
    public SpecialistSelectionContract.Decision select(SpecialistSelectionContract.Request request) throws Exception {
        Objects.requireNonNull(request);
        if (!enabled) return SpecialistSelectionContract.Decision.rejected(
            SpecialistSelectionContract.Outcome.BLOCKED_SPECIALIST);
        if (!Files.isRegularFile(database) || !Files.isReadable(database))
            throw new SQLException("Governed registry unavailable");
        return SpecialistSelectionContract.select(request, eligibleCandidates());
    }

    private List<SpecialistSelectionContract.Candidate> eligibleCandidates() throws Exception {
        if (!enabled) throw new SecurityException("Governed selection disabled");
        if (!Files.isRegularFile(database) || !Files.isReadable(database))
            throw new SQLException("Governed registry unavailable");
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        var candidates = new ArrayList<SpecialistSelectionContract.Candidate>();
        var unique = new HashSet<String>();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
             var statement = connection.prepareStatement(
                 "SELECT id,version,status,manifest_sha256,manifest_json,origin,revision " +
                 "FROM specialist_registry_profiles ORDER BY id,version");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString("id");
                String version = rows.getString("version");
                String raw = rows.getString("manifest_json");
                var verified = SpecialistRegistryManifest.validate(mapper, raw);
                if (!verified.id().equals(id) || !verified.version().equals(version)
                    || !verified.canonicalJson().equals(raw)
                    || !verified.sha256().equals(rows.getString("manifest_sha256"))
                    || rows.getLong("revision") < 0
                    || !unique.add(id + "@" + version))
                    throw new SecurityException("Governed registry integrity violation");
                String status = rows.getString("status");
                SpecialistRegistryStore.Status.valueOf(status); // reject unknown states even on inactive records
                if (!"ACTIVE".equals(status)) continue;
                // Legacy bootstrap is handled by the frozen v1 adapter, not silently duplicated in v2.
                if ("TRUSTED_LEGACY_BOOTSTRAP".equals(rows.getString("origin"))) continue;
                if (!"PROPOSAL".equals(rows.getString("origin")))
                    throw new SecurityException("Unknown governed profile origin");
                var node = mapper.readTree(raw);
                var capabilities = new HashSet<String>();
                for (var competency : node.path("competencies"))
                    capabilities.add(competency.path("capabilityId").asText());
                var tools = new HashSet<String>();
                for (var tool : node.path("requiredTools")) tools.add(tool.asText());
                candidates.add(new SpecialistSelectionContract.Candidate(id, version,
                    capabilities, tools, node.path("maxConcurrent").asInt(),
                    node.path("costUnits").asInt(), SpecialistSelectionContract.Source.GOVERNED_V2));
            }
        }
        return List.copyOf(candidates);
    }
}
