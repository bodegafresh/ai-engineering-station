package dev.aies.control.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommunicationStoreTest {
    @TempDir Path temp;
    @Test void recordsSurviveRestart() throws Exception {
        Path db=temp.resolve("communications.sqlite");
        var mapper=new ObjectMapper();
        var artifact=CommunicationContracts.validate(new CommunicationContracts.Artifact(
                "1.0","wf-001","task-001","analyst","goals","text/markdown",
                "# Goals\nAcceptance criteria",List.of()));
        String id;
        try(var store=new CommunicationStore(db,mapper)) {
            var saved=store.append("ARTIFACT",artifact.workflowId(),artifact.taskId(),artifact);
            id=saved.id();
            assertEquals(1,store.list("wf-001").size());
        }
        try(var store=new CommunicationStore(db,mapper)) {
            var saved=store.find(id).orElseThrow();
            assertEquals(artifact,saved.payload());
            assertEquals(64,saved.sha256().length());
            assertEquals(0,store.list("wf-other").size());
        }
    }
}
