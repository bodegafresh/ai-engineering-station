package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceIdempotencyTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;
    @Test void replayAfterReopenDoesNotCreateDecisionOrOutboxEvent() throws Exception {
        Path db=temp.resolve("idempotency.sqlite");
        String hash;
        try(var registry=new SpecialistRegistryStore(db,mapper)) {
            hash=registry.propose(MANIFEST).manifestSha256();
        }
        var actor=new SpecialistGovernanceService.Principal("operator-one",
            Set.of("specialists:validate","specialists:approve"));
        SpecialistGovernanceService.Decision first;
        try(var service=new SpecialistGovernanceService(db,mapper)) {
            first=service.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,actor,"request-000001");
        }
        try(var service=new SpecialistGovernanceService(db,mapper)) {
            var replay=service.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,actor,"request-000001");
            assertEquals(first,replay);
            assertThrows(SecurityException.class,()->service.transition(
                "documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.APPROVE,0,hash,actor,"request-000001"));
            assertThrows(SecurityException.class,()->service.transition(
                "documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,
                new SpecialistGovernanceService.Principal("operator-two",Set.of("specialists:validate")),
                "request-000001"));
        }
        try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+db);
            Statement s=c.createStatement();
            ResultSet rs=s.executeQuery("SELECT count(*) FROM specialist_registry_decisions")) {
            assertTrue(rs.next());
            assertEquals(1,rs.getInt(1));
        }
    }

    @Test void rejectedTransitionDoesNotReserveCommandKey() throws Exception {
        Path db=temp.resolve("rollback.sqlite");
        String hash;
        try(var registry=new SpecialistRegistryStore(db,mapper)) {
            hash=registry.propose(MANIFEST).manifestSha256();
        }
        var actor=new SpecialistGovernanceService.Principal("operator-one",Set.of("specialists:validate"));
        try(var service=new SpecialistGovernanceService(db,mapper)) {
            assertThrows(SQLException.class,()->service.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,9,hash,actor,"request-000002"));
            var accepted=service.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,actor,"request-000002");
            assertEquals(1,accepted.revision());
        }
    }
}
