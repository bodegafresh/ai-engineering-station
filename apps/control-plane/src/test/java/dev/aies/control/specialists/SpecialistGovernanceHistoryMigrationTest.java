package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceHistoryMigrationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;
    @Test void historicalDecisionsAreNeverDispatchable() throws Exception {
        Path db=temp.resolve("legacy.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(db,mapper)) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        var actor=new SpecialistGovernanceService.Principal("operator-test",
            Set.of("specialists:validate","specialists:approve"));
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,actor);
            var result=SpecialistGovernanceHistoryMigration.migrate(db,mapper);
            assertEquals(1,result.historicalRows());
            assertFalse(result.alreadyApplied());
            assertEquals(0,SpecialistGovernanceHistoryMigration.dispatchableCount(db));
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.APPROVE,1,hash,actor);
        }
        assertEquals(1,SpecialistGovernanceHistoryMigration.dispatchableCount(db));
        assertEquals(2,SpecialistGovernanceOutbox.count(db));
        SpecialistGovernanceOutbox.verify(db);
        var again=SpecialistGovernanceHistoryMigration.migrate(db,mapper);
        assertTrue(again.alreadyApplied());
        assertEquals(1,again.historicalRows());
        assertEquals(1,SpecialistGovernanceHistoryMigration.dispatchableCount(db));
    }

    @Test void corruptedHistoryIsRejectedBeforeMigration() throws Exception {
        Path db=temp.resolve("bad.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(db,mapper)) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,
                new SpecialistGovernanceService.Principal("operator-test",Set.of("specialists:validate")));
        }
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement()) {
            s.execute("UPDATE specialist_registry_decisions SET actor='' WHERE to_revision=1");
        }
        assertThrows(SecurityException.class,()->SpecialistGovernanceHistoryMigration.migrate(db,mapper));
    }
}
