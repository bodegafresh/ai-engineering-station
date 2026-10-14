package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistOperatorGatewayTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String VALIDATOR = "v".repeat(40);
    private static final String APPROVER = "a".repeat(40);
    private static final String ACTIVATOR = "z".repeat(40);
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """;

    private SpecialistOperatorGateway.Operator op(String id, String secret, String... scopes) {
        return new SpecialistOperatorGateway.Operator(id,
            SpecialistOperatorGateway.sha256(secret), Set.of(scopes));
    }

    @Test void requiresDistinctAuthenticatedOperatorsAndPersists() throws Exception {
        Path db=temp.resolve("registry.sqlite");
        String hash;
        try (var store=new SpecialistRegistryStore(db,mapper)) {
            hash=store.propose(MANIFEST).manifestSha256();
        }
        var operators=List.of(
            op("validator",VALIDATOR,"specialists:validate"),
            op("approver",APPROVER,"specialists:approve","specialists:activate"),
            op("activator",ACTIVATOR,"specialists:activate"));
        try(var gateway=new SpecialistOperatorGateway(db,mapper,operators)) {
            assertThrows(SecurityException.class,()->gateway.transition("wrong", "documentation.specialist",
                "1.0.0",SpecialistGovernanceService.Action.VALIDATE,0,hash));
            assertThrows(SecurityException.class,()->gateway.transition(VALIDATOR, "documentation.specialist",
                "1.0.0",SpecialistGovernanceService.Action.APPROVE,0,hash));
            assertEquals("VALIDATED",gateway.transition(VALIDATOR,"documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.VALIDATE,0,hash).state());
            assertEquals("APPROVED",gateway.transition(APPROVER,"documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.APPROVE,1,hash).state());
            assertThrows(SQLException.class,()->gateway.transition(APPROVER,"documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.ACTIVATE,2,hash));
            try(var store=new SpecialistRegistryStore(db,mapper)) {
                assertEquals(2,store.find("documentation.specialist","1.0.0").orElseThrow().revision());
            }
            assertEquals("ACTIVE",gateway.transition(ACTIVATOR,"documentation.specialist","1.0.0",
                SpecialistGovernanceService.Action.ACTIVATE,2,hash).state());
        }
        SpecialistGovernanceIntegrity.verify(db,mapper);
    }

    @Test void refusesAmbiguousBindingsAndWeakSecrets() throws Exception {
        assertThrows(IllegalArgumentException.class,()->SpecialistOperatorGateway.sha256("short"));
        Path db=temp.resolve("empty.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)) { /* initialize */ }
        var same=op("same",VALIDATOR,"specialists:validate");
        assertThrows(SecurityException.class,()->new SpecialistOperatorGateway(db,mapper,List.of(same,same)));
    }
}
