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

/** End-to-end governance commands in separate JVM processes. */
class SpecialistGovernanceProcessRecoveryTest {
    @TempDir Path temp;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    @Test void concurrentProcessesExecuteRealTransitionWithSingleWinner() throws Exception {
        Path db=temp.resolve("concurrent.sqlite");
        String hash=propose(db);
        Path barrier=temp.resolve("start");
        Process first=spawn(db,hash,"command-jvm-a",barrier,"normal",1);
        Process second=spawn(db,hash,"command-jvm-b",barrier,"normal",2);
        try {
            Files.createFile(barrier);
            int a=exit(first),b=exit(second);
            assertEquals(List.of(0,10),sorted(a,b),"Exactly one transition commits; inspect worker logs");
        } finally {kill(first);kill(second);}
        assertCounts(db,1,1);
    }

    @Test void responseLostAfterCommitCanBeReplayedAfterJvmRestart() throws Exception {
        Path db=temp.resolve("restart.sqlite");
        String hash=propose(db);
        Path barrier=temp.resolve("start");
        Files.createFile(barrier);
        Process first=spawn(db,hash,"durable-request-0001",barrier,"halt-after",1);
        try { assertEquals(32,exit(first)); } finally {kill(first);}
        assertCounts(db,1,1);
        Process replay=spawn(db,hash,"durable-request-0001",barrier,"normal",2);
        try {assertEquals(0,exit(replay));} finally {kill(replay);}
        assertCounts(db,1,1);
    }

    @Test void haltedBeforeTransitionDoesNotReserveCommandKey() throws Exception {
        Path db=temp.resolve("before.sqlite");
        String hash=propose(db);
        // Initialize the governance schema before simulating a crash before transition.
        // A halted worker never constructs SpecialistGovernanceService, so without
        // this setup the decisions/idempotency tables do not yet exist.
        try (var ignored = new SpecialistGovernanceService(db, new ObjectMapper())) {
            // Schema initialization only; no governance command is executed.
        }
        Path barrier=temp.resolve("start");
        Files.createFile(barrier);
        Process interrupted=spawn(db,hash,"durable-request-0002",barrier,"halt-before",1);
        try {assertEquals(31,exit(interrupted));} finally {kill(interrupted);}
        assertCounts(db,0,0);
        Process retry=spawn(db,hash,"durable-request-0002",barrier,"normal",2);
        try {assertEquals(0,exit(retry));} finally {kill(retry);}
        assertCounts(db,1,1);
    }

    @Test void crashInsideOpenTransactionRollsBackDecisionCommandAndOutbox() throws Exception {
        Path db=temp.resolve("intratransaction.sqlite");
        String hash=propose(db);
        try(var service=new SpecialistGovernanceService(db,new ObjectMapper())) { }
        SpecialistGovernanceOutbox.install(db);
        Path barrier=temp.resolve("start-inside");
        Files.createFile(barrier);
        Process crashed=spawn(db,hash,"durable-inside-0001",barrier,"halt-in-transaction",1);
        try { assertEquals(33,exit(crashed),Files.readString(temp.resolve("worker-1.log"))); }
        finally {kill(crashed);}
        assertCounts(db,0,0);
        assertEquals(0,SpecialistGovernanceOutbox.count(db));
        SpecialistGovernanceSnapshot.verify(db);
        Process retry=spawn(db,hash,"durable-inside-0001",barrier,"normal",2);
        try { assertEquals(0,exit(retry),Files.readString(temp.resolve("worker-2.log"))); }
        finally {kill(retry);}
        assertCounts(db,1,1);
        assertEquals(1,SpecialistGovernanceOutbox.count(db));
        SpecialistGovernanceOutbox.verify(db);
    }

    private String propose(Path db) throws Exception {
        try(var registry=new SpecialistRegistryStore(db,new ObjectMapper())) {
            return registry.propose(MANIFEST).manifestSha256();
        }
    }
    private Process spawn(Path db,String hash,String key,Path barrier,String mode,int index) throws Exception {
        // java.class.path in Gradle can be a launcher only. Build from actual class code sources.
        String cp=String.join(File.pathSeparator,
            source(SpecialistGovernanceProcessWorker.class),
            source(SpecialistGovernanceService.class),
            source(SpecialistRegistryStore.class),
            source(com.fasterxml.jackson.databind.ObjectMapper.class),
            source(com.fasterxml.jackson.core.JsonFactory.class),
            source(com.fasterxml.jackson.annotation.JsonProperty.class),
            source(org.sqlite.JDBC.class),
            source(org.slf4j.LoggerFactory.class));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",cp,SpecialistGovernanceProcessWorker.class.getName(),
            db.toString(),hash,key,barrier.toString(),mode)
            .redirectErrorStream(true)
            .redirectOutput(temp.resolve("worker-"+index+".log").toFile())
            .start();
    }
    private static String source(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static int exit(Process p) throws Exception {
        assertTrue(p.waitFor(30,TimeUnit.SECONDS),"JVM worker timed out");
        return p.exitValue();
    }
    private static void kill(Process p) {if(p.isAlive()) p.destroyForcibly();}
    private static List<Integer> sorted(int a,int b) {
        List<Integer> values=new ArrayList<>(List.of(a,b));Collections.sort(values);return values;
    }
    private static void assertCounts(Path db,int decisions,int commands) throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement()) {
            try(ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_registry_decisions")) {
                assertTrue(rs.next());assertEquals(decisions,rs.getInt(1));
            }
            try(ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_governance_idempotency")) {
                assertTrue(rs.next());assertEquals(commands,rs.getInt(1));
            }
        }
    }
}
