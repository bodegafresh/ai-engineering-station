package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionEvidenceVerifierTest {
    @TempDir Path directory;
    @Test void validatesStoredArtifactAndRejectsForeignWorkflowAndWrongKind() throws Exception {
        try (var store = new CommunicationStore(directory.resolve("communication.sqlite"), new ObjectMapper())) {
            var artifact = CommunicationContracts.validate(new CommunicationContracts.Artifact(
                "1.0", "workflow-a", "task-a", "specialist-a", "execution-result", "text/markdown",
                "Actual output with source context", List.of()));
            var saved = store.append("ARTIFACT", "workflow-a", "task-a", artifact);
            var ref = "artifact://communication/" + saved.id();
            assertEquals(saved.id(), ExecutionEvidenceVerifier.verify(store,"workflow-a","task-a",ref).id());
            assertThrows(SecurityException.class, () -> ExecutionEvidenceVerifier.verify(store,"workflow-b","task-a",ref));
            assertThrows(SecurityException.class, () -> ExecutionEvidenceVerifier.verify(store,"workflow-a","task-b",ref));
            assertThrows(IllegalArgumentException.class, () -> ExecutionEvidenceVerifier.verify(store,"workflow-a","task-a","https://example.com"));
            assertThrows(IllegalArgumentException.class, () -> ExecutionEvidenceVerifier.verify(store,"workflow-a","task-a","artifact://communication/unknown"));
        }
    }
}
