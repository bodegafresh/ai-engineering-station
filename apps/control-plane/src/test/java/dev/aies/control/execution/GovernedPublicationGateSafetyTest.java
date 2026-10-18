package dev.aies.control.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Negative lock-file integrity tests; cross-JVM ordering is covered separately. */
class GovernedPublicationGateSafetyTest {
    @Test void refusesSymlinkedLockFileWithoutModifyingTarget() throws Exception {
        Path root = Files.createTempDirectory("aies-gate-symlink-");
        Path target = root.resolve("sensitive.txt");
        Files.writeString(target, "must-remain-unchanged");
        Path db = root.resolve("policies.sqlite");
        Files.createSymbolicLink(root.resolve("policies.sqlite.publication.lock"), target);
        assertThrows(SecurityException.class, () -> {
            try (var ignored = GovernedPublicationGate.acquire(db)) { fail("Must not acquire unsafe lock"); }
        });
        assertEquals("must-remain-unchanged", Files.readString(target));
    }

    @Test void refusesDirectoryAsLockFile() throws Exception {
        Path root = Files.createTempDirectory("aies-gate-dir-");
        Files.createDirectory(root.resolve("policies.sqlite.publication.lock"));
        assertThrows(SecurityException.class, () -> {
            try (var ignored = GovernedPublicationGate.acquire(root.resolve("policies.sqlite"))) {
                fail("Must not acquire directory lock");
            }
        });
    }

    @Test void recoversAfterRejectedLockFileIsRemoved() throws Exception {
        Path root = Files.createTempDirectory("aies-gate-recover-");
        Path db = root.resolve("policies.sqlite");
        Path lock = root.resolve("policies.sqlite.publication.lock");
        Files.createDirectory(lock);
        assertThrows(SecurityException.class, () -> GovernedPublicationGate.acquire(db));
        Files.delete(lock);
        try (var ignored = GovernedPublicationGate.acquire(db)) {
            assertTrue(Files.isRegularFile(lock));
        }
    }
}
