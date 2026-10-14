package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceServiceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private String manifest() {
        return """
            {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
            "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
            "competencies":[{"capabilityId":"documentation.technical.authoring",
            "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
            "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
            """;
    }
    private SpecialistGovernanceService.Principal operator() {
        return new SpecialistGovernanceService.Principal("operator-test",
            Set.of("specialists:validate","specialists:approve","specialists:activate",
                   "specialists:reject","specialists:deprecate","specialists:revoke"));
    }
    @Test void humanGovernanceIsDurableAndCasProtected() throws Exception {
        Path db=temp.resolve("registry.sqlite");
        String hash;
        try (var store=new SpecialistRegistryStore(db,mapper)) {
            hash=store.propose(manifest()).manifestSha256();
        }
        try (var governance=new SpecialistGovernanceService(db,mapper)) {
            assertThrows(SecurityException.class,()->governance.transition(
                "documentation.specialist","1.0.0",SpecialistGovernanceService.Action.ACTIVATE,
                0,hash,new SpecialistGovernanceService.Principal("model-agent",Set.of())));
            var validated=governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,operator());
            assertEquals("VALIDATED",validated.state());
            assertThrows(SQLException.class,()->governance.transition(
                "documentation.specialist","1.0.0",SpecialistGovernanceService.Action.APPROVE,0,hash,operator()));
            assertEquals("APPROVED",governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.APPROVE,1,hash,operator()).state());
            assertEquals("ACTIVE",governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.ACTIVATE,2,hash,operator()).state());
            assertEquals("REVOKED",governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.REVOKE,3,hash,operator()).state());
            assertThrows(IllegalStateException.class,()->governance.transition(
                "documentation.specialist","1.0.0",SpecialistGovernanceService.Action.ACTIVATE,4,hash,operator()));
        }
        try (var store=new SpecialistRegistryStore(db,mapper)) {
            var e=store.find("documentation.specialist","1.0.0").orElseThrow();
            assertEquals(SpecialistRegistryStore.Status.REVOKED,e.status());
            assertEquals(4,e.revision());
            assertEquals(hash,e.manifestSha256());
        }
        try (Connection conn=new org.sqlite.JDBC().connect("jdbc:sqlite:"+db,new java.util.Properties());
             Statement st=conn.createStatement();
             ResultSet rs=st.executeQuery("SELECT count(*) FROM specialist_registry_decisions")) {
            assertTrue(rs.next());assertEquals(4,rs.getInt(1));
        }
    }
    @Test void legacySeedsCannotBeGovernedByProposalPath() throws Exception {
        Path db=temp.resolve("legacy.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)) {store.bootstrapLegacy();}
        try(var store=new SpecialistRegistryStore(db,mapper);
            var governance=new SpecialistGovernanceService(db,mapper)) {
            var e=store.find("researcher","1.0.0").orElseThrow();
            assertThrows(SecurityException.class,()->governance.transition(
                "researcher","1.0.0",SpecialistGovernanceService.Action.REVOKE,
                0,e.manifestSha256(),operator()));
        }
    }
}
