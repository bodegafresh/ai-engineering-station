package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GovernedSpecialistSelectorTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String MANIFEST = """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"documentation.specialist",
        "version":"1.0.0","displayName":"Documentation","discipline":"engineering.software",
        "competencies":[{"capabilityId":"documentation.technical.authoring",
        "capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
        "roles":["ANALYST"],"requiredTools":["filesystem"],"maxConcurrent":1,"costUnits":3}
        """;
    private SpecialistSelectionContract.Request request(Set<String> tools) {
        return new SpecialistSelectionContract.Request(Set.of("documentation.technical.authoring"),tools,10);
    }
    @Test void disabledDoesNotCreateDatabase() throws Exception {
        Path db=temp.resolve("absent.sqlite");
        var selector=new GovernedSpecialistSelector(db,mapper,false);
        assertEquals(SpecialistSelectionContract.Outcome.BLOCKED_SPECIALIST,selector.select(request(Set.of())).outcome());
        assertFalse(java.nio.file.Files.exists(db));
        assertThrows(java.sql.SQLException.class,()->new GovernedSpecialistSelector(db,mapper,true).select(request(Set.of())));
    }
    @Test void onlyHumanActivatedEntriesEligibleAndRevocationImmediate() throws Exception {
        Path db=temp.resolve("registry.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(db,mapper)) { hash=store.propose(MANIFEST).manifestSha256(); }
        var selector=new GovernedSpecialistSelector(db,mapper,true);
        assertEquals(SpecialistSelectionContract.Outcome.NO_MATCH,selector.select(request(Set.of("filesystem"))).outcome());
        var actor=new SpecialistGovernanceService.Principal("human-operator",Set.of("specialists:validate","specialists:approve","specialists:activate","specialists:revoke"));
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.VALIDATE,0,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.APPROVE,1,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.ACTIVATE,2,hash,actor);
        }
        assertEquals(SpecialistSelectionContract.Outcome.NO_MATCH,selector.select(request(Set.of())).outcome());
        var selected=selector.select(request(Set.of("filesystem")));
        assertEquals(SpecialistSelectionContract.Outcome.SELECTED,selected.outcome());
        assertEquals("documentation.specialist",selected.specialistId());
        assertEquals(SpecialistSelectionContract.Source.GOVERNED_V2,selected.source());
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.REVOKE,3,hash,actor);
        }
        assertEquals(SpecialistSelectionContract.Outcome.NO_MATCH,selector.select(request(Set.of("filesystem"))).outcome());
    }
    @Test void tamperingInactiveEntryFailsClosedAndDoesNotRewriteDatabase() throws Exception {
        Path db=temp.resolve("tamper.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)) {store.propose(MANIFEST);}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement()) {
            s.executeUpdate("UPDATE specialist_registry_profiles SET manifest_sha256='bad' WHERE id='documentation.specialist'");
        }
        assertThrows(SecurityException.class,()->new GovernedSpecialistSelector(db,mapper,true).select(request(Set.of())));
    }
    @Test void dispatchRechecksIdentityCapabilityAndRevocation() throws Exception {
        Path db=temp.resolve("dispatch.sqlite");
        String manifest=MANIFEST.replace("[\"filesystem\"]", "[]");
        String hash;
        try(var store=new SpecialistRegistryStore(db,mapper)) {hash=store.propose(manifest).manifestSha256();}
        var selector=new GovernedSpecialistSelector(db,mapper,true);
        assertFalse(selector.eligibleForDispatch("documentation.specialist","documentation.technical.authoring"));
        var actor=new SpecialistGovernanceService.Principal("human-operator",Set.of(
            "specialists:validate","specialists:approve","specialists:activate","specialists:revoke"));
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.VALIDATE,0,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.APPROVE,1,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.ACTIVATE,2,hash,actor);
        }
        assertTrue(selector.eligibleForDispatch("documentation.specialist","documentation.technical.authoring"));
        assertFalse(selector.eligibleForDispatch("another.specialist","documentation.technical.authoring"));
        assertFalse(selector.eligibleForDispatch("documentation.specialist","research"));
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.REVOKE,3,hash,actor);
        }
        assertFalse(selector.eligibleForDispatch("documentation.specialist","documentation.technical.authoring"));
    }
    @Test void dispatchRejectsToolRequiringProfilesAndMissingRegistry() throws Exception {
        Path db=temp.resolve("tools.sqlite");
        String hash;
        try(var store=new SpecialistRegistryStore(db,mapper)) {hash=store.propose(MANIFEST).manifestSha256();}
        var actor=new SpecialistGovernanceService.Principal("human-operator",Set.of(
            "specialists:validate","specialists:approve","specialists:activate"));
        try(var governance=new SpecialistGovernanceService(db,mapper)) {
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.VALIDATE,0,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.APPROVE,1,hash,actor);
            governance.transition("documentation.specialist","1.0.0",SpecialistGovernanceService.Action.ACTIVATE,2,hash,actor);
        }
        assertFalse(new GovernedSpecialistSelector(db,mapper,true).eligibleForDispatch(
            "documentation.specialist","documentation.technical.authoring"));
        assertThrows(java.sql.SQLException.class,()->new GovernedSpecialistSelector(
            temp.resolve("missing.sqlite"),mapper,true).eligibleForDispatch("documentation.specialist","research"));
    }
    @Test void legacyBootstrapDoesNotLeakIntoV2() throws Exception {
        Path db=temp.resolve("legacy.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)) {store.bootstrapLegacy();}
        var decision=new GovernedSpecialistSelector(db,mapper,true).select(
            new SpecialistSelectionContract.Request(Set.of("research"),Set.of(),10));
        assertEquals(SpecialistSelectionContract.Outcome.NO_MATCH,decision.outcome());
    }
}
