package dev.aies.control.memory;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SqliteProjectMemoryTest {
    @Test void persistsIsolatesEvictsAndDeletes() throws Exception {
        var file = Files.createTempFile("aies-v017-", ".sqlite");
        try {
            try (var m = new SqliteProjectMemory(file, 2)) {
                m.remember("alpha", "decision", "quarkus persistent architecture");
                m.remember("beta", "decision", "quarkus unrelated project");
                m.remember("alpha", "incident", "vertx event bus timeout");
                m.remember("alpha", "decision", "quarkus memory design");
                assertEquals(1, m.retrieve("alpha", "quarkus", 10).size());
                assertEquals(1, m.retrieve("beta", "quarkus", 10).size());
                assertEquals(0, m.retrieve("alpha", "unrelated", 10).size());
                assertThrows(IllegalArgumentException.class, () -> m.retrieve("../escape", "quarkus", 10));
                assertDoesNotThrow(() -> m.retrieve("alpha", "quarkus OR *", 10));
            }
            try (var reopened = new SqliteProjectMemory(file, 2)) {
                assertEquals(1, reopened.retrieve("alpha", "quarkus", 10).size());
                assertEquals(2, reopened.deleteProject("alpha"));
                assertTrue(reopened.retrieve("alpha", "quarkus", 10).isEmpty());
                assertEquals(1, reopened.retrieve("beta", "quarkus", 10).size());
            }
        } finally { Files.deleteIfExists(file); }
    }
    @Test void budgetsDoNotExceedLimit() {
        var entries = List.of(new SqliteProjectMemory.Entry("id", "p", "decision", "x".repeat(400), java.time.Instant.now()));
        var result = new MemoryContextBudget().assemble(entries, 64);
        assertTrue(result.estimatedTokens() <= 64);
        assertEquals(1, result.omitted());
    }
}
