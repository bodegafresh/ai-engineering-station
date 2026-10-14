package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** H01-C5: certify structural recovery AND semantic reconciliation on restored bytes. */
class SpecialistGovernanceRecoveryCertificationTest {
    @TempDir Path dir;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    @Test void restoredGovernanceReconcilesAndTamperingFailsClosed() throws Exception {
        Path source = dir.resolve("source.sqlite");
        String hash;
        try (var registry = new SpecialistRegistryStore(source, new ObjectMapper())) {
            hash = registry.propose(MANIFEST).manifestSha256();
        }
        try (var ignored = new SpecialistGovernanceService(source, new ObjectMapper())) { }
        // Complete the historical baseline schema before new decisions are recorded.
        var migration = SpecialistGovernanceHistoryMigration.migrate(source, new ObjectMapper());
        assertEquals(0, migration.historicalRows());
        assertEquals(0, SpecialistGovernanceHistoryMigration.dispatchableCount(source));
        try (var service = new SpecialistGovernanceService(source, new ObjectMapper())) {
            service.transition("documentation.specialist", "1.0.0",
                SpecialistGovernanceService.Action.VALIDATE, 0, hash,
                new SpecialistGovernanceService.Principal("operator-certification", Set.of("specialists:validate")),
                "h01-c5-certification-command");
        }
        var before = SpecialistGovernanceReconciler.verify(source);
        assertEquals(1, before.decisions());
        assertEquals(1, before.commands());
        assertEquals(1, before.outbox());
        SpecialistGovernanceSnapshot.verify(source);

        Path backup = dir.resolve("backup.sqlite");
        Path restored = dir.resolve("restored.sqlite");
        runRecovery("backup", source, backup);
        runRecovery("verify", backup);
        runRecovery("restore", backup, restored);
        runRecovery("verify", restored);

        var after = SpecialistGovernanceReconciler.verify(restored);
        assertEquals(before, after, "Recovery must preserve governance counts and baseline");
        SpecialistGovernanceSnapshot.verify(restored);
        SpecialistGovernanceOutbox.verify(restored);
        assertThrows(AssertionError.class, () -> runRecovery("restore", backup, restored),
            "Restore must never overwrite an existing database");

        // A structurally valid database may still contain semantically corrupted governance data.
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + restored);
             Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate("UPDATE specialist_governance_outbox SET actor='tampered'"));
        }
        runRecovery("verify", restored); // SQLite structural integrity still passes.
        assertThrows(SecurityException.class, () -> SpecialistGovernanceReconciler.verify(restored));
        assertEquals(before, SpecialistGovernanceReconciler.verify(source), "Original must remain untouched");
        assertEquals(before, SpecialistGovernanceReconciler.verify(backup), "Backup must remain untouched");
    }

    @Test void structurallyValidButSemanticallyIncompleteBackupMustBeRejected() throws Exception {
        Path source = dir.resolve("incomplete.sqlite");
        try (var registry = new SpecialistRegistryStore(source, new ObjectMapper())) {
            registry.propose(MANIFEST);
        }
        try (var ignored = new SpecialistGovernanceService(source, new ObjectMapper())) { }
        // No outbox migration: the schema is incomplete, even though SQLite integrity_check passes.
        Path backup = dir.resolve("incomplete-backup.sqlite");
        Path restored = dir.resolve("incomplete-restored.sqlite");
        runRecovery("backup", source, backup);
        runRecovery("restore", backup, restored);
        assertThrows(java.sql.SQLException.class, () -> SpecialistGovernanceReconciler.verify(restored));
    }

    private static void runRecovery(String operation, Path source, Path... destination) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("scripts/aies-sqlite-recovery.py"))) {
            root = root.getParent();
        }
        assertNotNull(root, "H01-C4 recovery script not found in repository ancestors");
        var cmd = new java.util.ArrayList<String>();
        cmd.add("python3");
        cmd.add(root.resolve("scripts/aies-sqlite-recovery.py").toString());
        cmd.add(operation);
        cmd.add(source.toString());
        for (Path path : destination) cmd.add(path.toString());
        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Recovery subprocess timed out: " + operation);
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "Recovery operation failed: " + operation + " " + output);
    }
}
