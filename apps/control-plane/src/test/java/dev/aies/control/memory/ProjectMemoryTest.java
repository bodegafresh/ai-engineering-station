package dev.aies.control.memory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectMemoryTest {
    @Test void isolatesProjectsAndEvictsOldEntries() {
        var memory = new ProjectMemory(2);
        memory.remember("alpha", "decision", "quarkus architecture");
        memory.remember("beta", "decision", "quarkus elsewhere");
        memory.remember("alpha", "incident", "vertx event bus timeout");
        memory.remember("alpha", "decision", "quarkus memory design");
        assertEquals(1, memory.retrieve("alpha", "quarkus", 10).size());
        assertEquals(1, memory.retrieve("beta", "quarkus", 10).size());
        assertEquals(0, memory.retrieve("alpha", "elsewhere", 10).size());
    }
    @Test void rejectsUnsafeInputs() {
        var memory = new ProjectMemory(3);
        assertThrows(IllegalArgumentException.class, () -> memory.remember("../bad", "decision", "x"));
        assertThrows(IllegalArgumentException.class, () -> memory.retrieve("ok", "", 5));
    }
}
