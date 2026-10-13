package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionIdempotencyTest {
    @TempDir Path root;
    private static final String HASH = "a".repeat(64);

    @Test void destinationDeduplicatesAfterRestartAndRejectsConflicts() throws Exception {
        var db = root.resolve("communication.sqlite");
        var mapper = new ObjectMapper();
        var artifact = new CommunicationContracts.Artifact(CommunicationContracts.VERSION,
            "wf", "task", "software-engineer", "execution-result", "text/markdown", "# Result", List.of());
        String id;
        try (var store = new CommunicationStore(db, mapper)) {
            id = store.appendIdempotent("execution-attempt-1-artifact", "ARTIFACT", "wf", "task", artifact).id();
            assertEquals(id, store.appendIdempotent("execution-attempt-1-artifact", "ARTIFACT", "wf", "task", artifact).id());
            assertEquals(1, store.list("wf").size());
        }
        try (var store = new CommunicationStore(db, mapper)) {
            assertEquals(id, store.appendIdempotent("execution-attempt-1-artifact", "ARTIFACT", "wf", "task", artifact).id());
            var conflicting = new CommunicationContracts.Artifact(CommunicationContracts.VERSION,
                "wf", "task", "software-engineer", "execution-result", "text/markdown", "# Different", List.of());
            assertThrows(SecurityException.class, () -> store.appendIdempotent(
                "execution-attempt-1-artifact", "ARTIFACT", "wf", "task", conflicting));
            assertEquals(1, store.list("wf").size());
        }
    }

    @Test void effectJournalRejectsRegressionsAndConflictingReplay() throws Exception {
        var mapper = new ObjectMapper();
        var db = root.resolve("execution-handoff.sqlite");
        String token;
        try (var store = new ExecutionHandoffStore(db, mapper)) {
            store.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("a")));
            store.authorizeCapability(new ExecutionCapabilityAuthorization(
                "wf-execution", "a", "workspace-documentation", "src/A.java", HASH));
            token = store.claim("wf-execution", 3, 60).orElseThrow().leaseToken();
            assertThrows(IllegalStateException.class, () -> store.recordEffect(
                "wf-execution", "a", token, "FILE_WRITTEN", null, "a.md", HASH));
            store.recordEffect("wf-execution", "a", token, "PREPARED", null, null, null);
            store.recordEffect("wf-execution", "a", token, "PREPARED", null, null, null);
            store.recordEffect("wf-execution", "a", token, "FILE_WRITTEN", null, "a.md", HASH);
            assertThrows(SecurityException.class, () -> store.recordEffect(
                "wf-execution", "a", token, "FILE_WRITTEN", null, "other.md", HASH));
            store.recordEffect("wf-execution", "a", token, "ARTIFACT_RECORDED", "record", "a.md", HASH);
            assertThrows(IllegalStateException.class, () -> store.recordEffect(
                "wf-execution", "a", token, "PREPARED", null, null, null));
        }
        try (var store = new ExecutionHandoffStore(db, mapper)) {
            assertEquals("ARTIFACT_RECORDED", store.effect("wf-execution", "a", token).orElseThrow().phase());
        }
    }
}
