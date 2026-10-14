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

/** H01-C2: an uncommitted JVM transaction must not strand a competing process. */
class SpecialistGovernanceCrashContentionTest {
    @TempDir Path temp;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    @Test void competingJvmCompletesAfterUncommittedOwnerCrashes() throws Exception {
        for (int round = 0; round < 3; round++) {
            Path folder=Files.createDirectory(temp.resolve("round-"+round));
            Path db=folder.resolve("governance.sqlite");
            String hash;
            try(var registry=new SpecialistRegistryStore(db,new ObjectMapper())) {
                hash=registry.propose(MANIFEST).manifestSha256();
            }
            try(var ignored=new SpecialistGovernanceService(db,new ObjectMapper())) { }
            SpecialistGovernanceOutbox.install(db);
            Path barrier=folder.resolve("start");
            Files.createFile(barrier);
            Process owner=null, contender=null;
            try {
                owner=spawn(db,hash,"owner-key-"+round,barrier,"halt-in-transaction-signaled",folder.resolve("owner.log"));
                waitForFile(Path.of(barrier+".inside"),owner);
                // The owner has already written all rows but still holds its SQLite transaction.
                contender=spawn(db,hash,"contender-key-"+round,barrier,"normal",folder.resolve("contender.log"));
                assertTrue(contender.isAlive(),"Contender should be running while owner holds write lock");
                Files.createFile(Path.of(barrier+".release"));
                assertEquals(33,exit(owner),Files.readString(folder.resolve("owner.log")));
                assertEquals(0,exit(contender),Files.readString(folder.resolve("contender.log")));
                assertEquals(1,count(db,"specialist_registry_decisions"));
                assertEquals(1,count(db,"specialist_governance_idempotency"));
                assertEquals(1,SpecialistGovernanceOutbox.count(db));
                SpecialistGovernanceSnapshot.verify(db);
                SpecialistGovernanceOutbox.verify(db);
                // The command belonging to the crashed owner must remain reusable.
                Process replay=spawn(db,hash,"owner-key-"+round,barrier,"normal",folder.resolve("replay.log"));
                try {
                    assertEquals(10,exit(replay),"Aborted command was not committed; stale CAS must be rejected");
                } finally {kill(replay);}
                assertEquals(1,count(db,"specialist_governance_idempotency"));
            } finally {kill(owner);kill(contender);}
        }
    }
    private static void waitForFile(Path marker,Process p) throws Exception {
        long deadline=System.nanoTime()+20_000_000_000L;
        while(!Files.exists(marker)) {
            if(!p.isAlive()) fail("Owner exited before transaction marker, exit="+p.exitValue());
            if(System.nanoTime()>deadline) fail("Timed out waiting for transaction marker");
            Thread.sleep(10);
        }
    }
    private static Process spawn(Path db,String hash,String key,Path barrier,String mode,Path log) throws Exception {
        String cp=String.join(File.pathSeparator,
            source(SpecialistGovernanceProcessWorker.class),source(SpecialistGovernanceService.class),
            source(SpecialistRegistryStore.class),source(com.fasterxml.jackson.databind.ObjectMapper.class),
            source(com.fasterxml.jackson.core.JsonFactory.class),source(com.fasterxml.jackson.annotation.JsonProperty.class),
            source(org.sqlite.JDBC.class),source(org.slf4j.LoggerFactory.class));
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",cp,SpecialistGovernanceProcessWorker.class.getName(),db.toString(),hash,key,
            barrier.toString(),mode).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }
    private static String source(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static int exit(Process p) throws Exception {
        assertTrue(p.waitFor(35,TimeUnit.SECONDS),"JVM timed out");return p.exitValue();
    }
    private static void kill(Process p) {if(p!=null && p.isAlive()) p.destroyForcibly();}
    private static int count(Path db,String table) throws Exception {
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT count(*) FROM "+table)) {
            assertTrue(r.next());return r.getInt(1);
        }
    }
}
