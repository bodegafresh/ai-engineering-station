package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistRegistryStoreTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private String manifest(String id){
        return """
        {"schemaVersion":"2.0","kind":"SpecialistProfile","id":"%s","version":"1.0.0",
         "displayName":"Example","discipline":"engineering.software",
         "competencies":[{"capabilityId":"documentation.technical.authoring","capabilityVersion":"1.0.0","requiredProficiency":"ADVANCED"}],
         "roles":["ANALYST"],"requiredTools":[],"maxConcurrent":1,"costUnits":3}
        """.formatted(id);
    }
    @Test void draftIsDurableAndNeverAutoActivated() throws Exception {
        Path db=temp.resolve("registry.sqlite");
        String sha;
        try(var store=new SpecialistRegistryStore(db,mapper)){
            var e=store.propose(manifest("documentation.specialist"));
            assertEquals(SpecialistRegistryStore.Status.DRAFT,e.status());
            assertEquals(0,e.revision());sha=e.manifestSha256();
            assertThrows(SQLException.class,()->store.propose(manifest("documentation.specialist")));
        }
        try(var reopened=new SpecialistRegistryStore(db,mapper)){
            var e=reopened.find("documentation.specialist","1.0.0").orElseThrow();
            assertEquals(sha,e.manifestSha256());assertEquals(SpecialistRegistryStore.Status.DRAFT,e.status());
        }
    }
    @Test void canonicalizationIndependentOfObjectKeyOrder() throws Exception {
        String json=manifest("documentation.specialist");
        var node=mapper.readTree(json);
        var shuffled=mapper.createObjectNode();
        shuffled.set("costUnits",node.get("costUnits"));
        node.fields().forEachRemaining(f->{if(!f.getKey().equals("costUnits"))shuffled.set(f.getKey(),f.getValue());});
        assertEquals(SpecialistRegistryManifest.validate(mapper,json).sha256(),
            SpecialistRegistryManifest.validate(mapper,shuffled.toString()).sha256());
    }
    @Test void malformedAndImplicitPermissionsFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class,()->SpecialistRegistryManifest.validate(mapper,manifest("documentation.specialist").replace("\"roles\"","\"implicitGrant\":true,\"roles\"")));
        assertThrows(IllegalArgumentException.class,()->SpecialistRegistryManifest.validate(mapper,manifest("documentation.specialist").replace("\"requiredTools\":[]","\"requiredTools\":null")));
        assertThrows(IllegalArgumentException.class,()->SpecialistRegistryManifest.validate(mapper,manifest("documentation.specialist").replace("\"maxConcurrent\":1","\"maxConcurrent\":0")));
    }
    @Test void legacyBootstrapExactAndIdempotent() throws Exception {
        Path db=temp.resolve("legacy.sqlite");
        try(var store=new SpecialistRegistryStore(db,mapper)){
            assertEquals(4,store.bootstrapLegacy());assertEquals(0,store.bootstrapLegacy());
            assertEquals(4,store.list().size());
            var engineer=store.find("software-engineer","1.0.0").orElseThrow();
            assertEquals(SpecialistRegistryStore.Status.ACTIVE,engineer.status());
            var node=mapper.readTree(engineer.manifestJson());
            assertEquals(3,node.get("costUnits").intValue());
            assertEquals(1,node.get("maxConcurrent").intValue());
            assertEquals(Set.of("software","implementation"),Set.of(node.get("competencies").get(0).get("capabilityId").asText(),node.get("competencies").get(1).get("capabilityId").asText()));
        }
    }
    @Test void modifiedStoredBytesAreRejected() throws Exception {
        Path file=temp.resolve("tamper.sqlite");
        try(var store=new SpecialistRegistryStore(file,mapper)){store.propose(manifest("documentation.specialist"));}
        try(var conn=new org.sqlite.JDBC().connect("jdbc:sqlite:"+file,new Properties());var p=conn.prepareStatement("UPDATE specialist_registry_profiles SET manifest_json=replace(manifest_json,'Example','Changed')")){
            assertEquals(1,p.executeUpdate());
        }
        try(var store=new SpecialistRegistryStore(file,mapper)){
            assertThrows(SecurityException.class,()->store.find("documentation.specialist","1.0.0"));
        }
    }
    @Test void bootstrapCollisionRollsBack() throws Exception {
        Path file=temp.resolve("collision.sqlite");
        try(var store=new SpecialistRegistryStore(file,mapper)){
            store.propose(manifest("researcher"));
            assertThrows(SecurityException.class,store::bootstrapLegacy);
            assertEquals(1,store.list().size());
        }
    }
}
