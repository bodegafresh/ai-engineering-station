package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** H01-C1: repeated, real-JVM contention against the public governance service. */
class SpecialistGovernanceContentionTest {
    @TempDir Path temp;
    private static final int ROUNDS = 12;
    private static final int CONTENDERS = 4;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    @Test void sustainedIndependentJvmContentionHasExactlyOneWinnerPerDatabase() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Path folder = Files.createDirectory(temp.resolve("round-" + round));
            Path db = folder.resolve("governance.sqlite");
            String hash;
            try (var registry = new SpecialistRegistryStore(db, new ObjectMapper())) {
                hash = registry.propose(MANIFEST).manifestSha256();
            }
            try (var ignored = new SpecialistGovernanceService(db, new ObjectMapper())) { }
            SpecialistGovernanceOutbox.install(db);
            Path barrier = folder.resolve("start");
            List<Process> processes = new ArrayList<>();
            try {
                for (int index = 0; index < CONTENDERS; index++) {
                    processes.add(spawn(db, hash, "contention-" + round + "-" + index,
                        barrier, folder.resolve("worker-" + index + ".log")));
                }
                Files.createFile(barrier);
                int winners = 0;
                for (int index = 0; index < processes.size(); index++) {
                    Process process = processes.get(index);
                    assertTrue(process.waitFor(45, TimeUnit.SECONDS),
                        "Worker timeout in round " + round + ": " + index);
                    int code = process.exitValue();
                    assertTrue(code == 0 || code == 10,
                        "Unexpected worker exit " + code + ": " + Files.readString(folder.resolve("worker-" + index + ".log")));
                    if (code == 0) winners++;
                }
                assertEquals(1, winners, "Single CAS winner required in round " + round);
                assertEquals(1, count(db, "specialist_registry_decisions"));
                assertEquals(1, count(db, "specialist_governance_idempotency"));
                assertEquals(1, SpecialistGovernanceOutbox.count(db));
                SpecialistGovernanceSnapshot.verify(db);
            } finally {
                for (Process process : processes) {
                    if (process.isAlive()) process.destroyForcibly();
                }
            }
        }
    }

    private static Process spawn(Path db, String hash, String key, Path barrier, Path log) throws Exception {
        String cp = String.join(File.pathSeparator,
            source(SpecialistGovernanceProcessWorker.class),
            source(SpecialistGovernanceService.class),
            source(SpecialistRegistryStore.class),
            source(com.fasterxml.jackson.databind.ObjectMapper.class),
            source(com.fasterxml.jackson.core.JsonFactory.class),
            source(com.fasterxml.jackson.annotation.JsonProperty.class),
            source(org.sqlite.JDBC.class),
            source(org.slf4j.LoggerFactory.class));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", cp, SpecialistGovernanceProcessWorker.class.getName(),
            db.toString(), hash, key, barrier.toString(), "normal")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static String source(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static int count(Path db, String table) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}
