package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistGovernanceIntegrityTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;
    private Path prepared() throws Exception {
        Path file=temp.resolve("specialists.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(file,mapper)) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        var principal=new SpecialistGovernanceService.Principal("human-operator",
            Set.of("specialists:validate","specialists:approve","specialists:activate"));
        try(var governance=new SpecialistGovernanceService(file,mapper)) {
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash,principal);
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.APPROVE,1,hash,principal);
            governance.transition("documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.ACTIVATE,2,hash,principal);
        }
        return file;
    }
    private void sql(Path file,String query) throws Exception {
        try(var db=new org.sqlite.JDBC().connect("jdbc:sqlite:"+file,new java.util.Properties());
            var statement=db.createStatement()) { statement.executeUpdate(query); }
    }
    @Test void validHistoryAndLegacyBootstrapPass() throws Exception {
        Path file=prepared();
        SpecialistGovernanceIntegrity.verify(file,mapper);
        try(var store=new SpecialistRegistryStore(file,mapper)) { assertEquals(4,store.bootstrapLegacy()); }
        SpecialistGovernanceIntegrity.verify(file,mapper);
    }
    @Test void missingDecisionFailsClosed() throws Exception {
        Path file=prepared();
        sql(file,"DELETE FROM specialist_registry_decisions WHERE to_revision=2");
        assertThrows(SecurityException.class,()->SpecialistGovernanceIntegrity.verify(file,mapper));
    }
    @Test void forgedStateFailsClosed() throws Exception {
        Path file=prepared();
        sql(file,"UPDATE specialist_registry_profiles SET status='REVOKED'");
        assertThrows(SecurityException.class,()->SpecialistGovernanceIntegrity.verify(file,mapper));
    }
    @Test void alteredActionFailsClosed() throws Exception {
        Path file=prepared();
        sql(file,"UPDATE specialist_registry_decisions SET action='REVOKE' WHERE to_revision=2");
        assertThrows(SecurityException.class,()->SpecialistGovernanceIntegrity.verify(file,mapper));
    }
    @Test void orphanDecisionFailsClosed() throws Exception {
        Path file=prepared();
        sql(file,"DELETE FROM specialist_registry_profiles");
        assertThrows(SecurityException.class,()->SpecialistGovernanceIntegrity.verify(file,mapper));
    }
}
