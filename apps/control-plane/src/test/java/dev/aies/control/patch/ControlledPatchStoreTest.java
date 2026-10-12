package dev.aies.control.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledPatchStoreTest {
    @TempDir Path root;
    @Test void requiresApprovalAndDetectsStaleContent() throws Exception {
        Path project = Files.createDirectory(root.resolve("sample"));
        Path source = project.resolve("App.java");
        Files.writeString(source, "class App {}\n");
        ControlledPatchStore store = new ControlledPatchStore(root);
        var p = store.prepare("sample", "App.java", "class App { int x; }\n");
        assertEquals("class App {}\n", Files.readString(source));
        assertThrows(SecurityException.class, () -> store.apply(p.id()));
        assertThrows(IllegalStateException.class, () -> store.approve(p.id(), "bad", p.proposedSha256()));
        store.approve(p.id(), p.originalSha256(), p.proposedSha256());
        Files.writeString(source, "changed");
        assertThrows(IllegalStateException.class, () -> store.apply(p.id()));
        Files.writeString(source, "class App {}\n");
        assertEquals("APPLIED", store.apply(p.id()).status());
        assertEquals("class App { int x; }\n", Files.readString(source));
        assertThrows(SecurityException.class, () -> store.apply(p.id()));
    }
    @Test void rejectsTraversalAndSensitiveFiles() throws Exception {
        Path project = Files.createDirectory(root.resolve("sample"));
        Files.writeString(project.resolve("App.java"), "old");
        Files.writeString(project.resolve(".env"), "SECRET");
        var store = new ControlledPatchStore(root);
        assertThrows(IllegalArgumentException.class, () -> store.prepare("sample", "../other.java", "new"));
        assertThrows(IllegalArgumentException.class, () -> store.prepare("sample", ".env", "new"));
        assertThrows(IllegalArgumentException.class, () -> store.prepare("sample", "App.java", "old"));
    }
}
