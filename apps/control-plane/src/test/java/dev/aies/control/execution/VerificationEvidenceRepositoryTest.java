package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class VerificationEvidenceRepositoryTest {
    @TempDir Path tmp;
    @Test void evidenceSurvivesRestart() throws Exception {
        Path db=tmp.resolve("evidence.sqlite");
        var record=new VerificationEvidence("PASSED","java-gradle",0,false,"abc","test passed",Instant.now());
        try(var store=new VerificationEvidenceRepository(db)) {store.append("proposal",record);}
        try(var store=new VerificationEvidenceRepository(db)) {
            assertEquals(1,store.history("proposal").size());
            assertEquals(record,store.history("proposal").getFirst());
        }
    }
}
