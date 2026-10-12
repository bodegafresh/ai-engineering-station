package dev.aies.control.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSnapshotTest {
    @TempDir Path temp;

    @Test void readsOnlyAllowlistedProjectFiles() throws Exception {
        Path project = Files.createDirectory(temp.resolve("demo"));
        Files.writeString(project.resolve("App.java"), "class App {}");
        Files.writeString(project.resolve(".env"), "SECRET_TOKEN=should-not-leak");
        Files.writeString(project.resolve("password.properties"), "password=should-not-leak");
        Files.createDirectory(project.resolve("build"));
        Files.writeString(project.resolve("build/generated.java"), "hidden");
        var snapshot = WorkspaceSnapshot.read(temp, "demo");
        assertEquals(java.util.List.of("App.java"), snapshot.files());
        assertTrue(snapshot.context().contains("class App {}"));
        assertFalse(snapshot.context().contains("should-not-leak"));
        assertFalse(snapshot.context().contains("hidden"));
    }

    @Test void rejectsTraversalAndSymlinkedProjects() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSnapshot.read(temp, "../demo"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path link = temp.resolve("demo");
        Files.createSymbolicLink(link, outside);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceSnapshot.read(temp, "demo"));
    }

    @Test void skipsSymlinkedFilesAndLimitsSize() throws Exception {
        Path project = Files.createDirectory(temp.resolve("demo"));
        Path outside = Files.writeString(temp.resolve("private.java"), "secret content");
        Files.createSymbolicLink(project.resolve("linked.java"), outside);
        Files.writeString(project.resolve("large.java"), "x".repeat(9000));
        var snapshot = WorkspaceSnapshot.read(temp, "demo");
        assertTrue(snapshot.files().isEmpty());
        assertTrue(snapshot.truncated());
    }
}
