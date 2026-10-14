package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceMultiprocessTest {
    @TempDir Path temp;
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;
    @Test void independentServiceConnectionsCannotBothWinCas() throws Exception {
        Path db=temp.resolve("cas.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(db,new ObjectMapper())) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        var operator=new SpecialistGovernanceService.Principal("operator-test",Set.of("specialists:validate"));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<2;i++) {
                final String key="parallel-key-000"+i;
                attempts.add(pool.submit(()->{
                    start.await();
                    try(var service=new SpecialistGovernanceService(db,new ObjectMapper())) {
                        service.transition("documentation.specialist","1.0.0",
                            SpecialistGovernanceService.Action.VALIDATE,0,hash,operator,key);
                        return true;
                    } catch(SQLException | IllegalStateException e) {return false;}
                }));
            }
            start.countDown();
            int wins=0;
            for(var f:attempts) if(f.get(20,TimeUnit.SECONDS)) wins++;
            assertEquals(1,wins,"Exactly one CAS transition must commit");
        } finally {pool.shutdownNow();}
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement()) {
            try(ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_registry_decisions")) {
                assertTrue(rs.next()); assertEquals(1,rs.getLong(1));
            }
            try(ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_governance_idempotency")) {
                assertTrue(rs.next()); assertEquals(1,rs.getLong(1));
            }
        }
    }

    @Test void twoRealJvmsCompeteForOneDurableCommandKey() throws Exception {
        Path db=temp.resolve("process.sqlite");
        try(var service=new SpecialistGovernanceService(db,new ObjectMapper())) {
            // Schema initialization only.
        }
        Path signal=temp.resolve("go");
        // Gradle test worker uses an isolated classloader. Resolve code sources explicitly.
        String cp=String.join(File.pathSeparator,
            codeSource(SpecialistGovernanceConcurrentWorker.class),
            codeSource(org.sqlite.JDBC.class),
            codeSource(org.slf4j.LoggerFactory.class));
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        List<Process> workers=new ArrayList<>();
        try {
            for(int i=0;i<2;i++) {
                Process p=new ProcessBuilder(java,"-cp",cp,
                    SpecialistGovernanceConcurrentWorker.class.getName(),
                    db.toString(),"same-command-key",signal.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(temp.resolve("worker-"+i+".log").toFile())
                    .start();
                workers.add(p);
            }
            Files.createFile(signal);
            List<Integer> exits=new ArrayList<>();
            for(Process p:workers) {
                assertTimeoutPreemptively(Duration.ofSeconds(30),()->{
                    assertTrue(p.waitFor(25,TimeUnit.SECONDS),"Worker timed out");
                });
                exits.add(p.exitValue());
            }
            Collections.sort(exits);
            assertEquals(List.of(0,10),exits,
                "One process must win, one must receive unique-key conflict; logs: "+temp);
        } finally {
            for(Process p:workers) if(p.isAlive()) p.destroyForcibly();
        }
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement();
            ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_governance_idempotency")) {
            assertTrue(rs.next()); assertEquals(1,rs.getLong(1));
        }
    }

    private static String codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
