package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceOutboxTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    @Test void outboxIsAtomicAndInstallIsIdempotent() throws Exception {
        Path db=temp.resolve("registry.sqlite");
        String hash;
        try (var store=new SpecialistRegistryStore(db,mapper)) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            SpecialistGovernanceOutbox.install(db);
            SpecialistGovernanceOutbox.install(db);
            var principal=new SpecialistGovernanceService.Principal("operator-one",Set.of("specialists:validate"));
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,principal);
            assertEquals(1,SpecialistGovernanceOutbox.count(db));
            assertThrows(SQLException.class,()->governance.transition(
                "documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,principal));
            assertEquals(1,SpecialistGovernanceOutbox.count(db));
        }
        SpecialistGovernanceOutbox.verify(db);
    }

    @Test void tamperingIsDetected() throws Exception {
        Path db=temp.resolve("tamper.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)) { store.propose(MANIFEST); }
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            SpecialistGovernanceOutbox.install(db);
        }
        SpecialistGovernanceOutbox.verify(db);
        try(Connection conn=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=conn.createStatement()) {
            s.execute("INSERT INTO specialist_governance_outbox VALUES " +
                "(999,'fake','1.0.0',1,'APPROVE','bad','attacker','now')");
        }
        assertThrows(SecurityException.class,()->SpecialistGovernanceOutbox.verify(db));
    }
}
