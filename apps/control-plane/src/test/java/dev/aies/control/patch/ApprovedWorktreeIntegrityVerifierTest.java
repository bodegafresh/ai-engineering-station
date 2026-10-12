package dev.aies.control.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ApprovedWorktreeIntegrityVerifierTest {
    @TempDir Path temp;
    private static void git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), output);
    }
    @Test void acceptsApprovedChangeAndRejectsTamperingAndExtraFiles() throws Exception {
        Path root = temp.resolve("project");
        Files.createDirectory(root);
        git(root, "init");
        Files.writeString(root.resolve("README.md"), "before\n");
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
        git(root, "add", "README.md", "gradlew");
        git(root, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial");
        Path worktree = temp.resolve("approved-worktree-" + root.getFileName());
        git(root, "worktree", "add", "--detach", worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        var change = new WorkspaceChangeService.Change("README.md",
                PatchDigest.sha256("before\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "after\n");
        var proposal = new WorkspaceChangeService.Proposal(UUID.randomUUID().toString(), "sample",
                "APPLIED_IN_WORKTREE", "reviewer", PatchDigest.fingerprint("sample", List.of(change)),
                List.of(change), Instant.now());
        Files.writeString(worktree.resolve("README.md"), "after\n");
        var verifier = new ApprovedWorktreeIntegrityVerifier();
        assertDoesNotThrow(() -> verifier.verify(worktree, proposal));
        Files.writeString(worktree.resolve("README.md"), "tampered\n");
        assertThrows(SecurityException.class, () -> verifier.verify(worktree, proposal));
        Files.writeString(worktree.resolve("README.md"), "after\n");
        Files.writeString(worktree.resolve("untracked.txt"), "extra");
        assertThrows(SecurityException.class, () -> verifier.verify(worktree, proposal));
        Files.delete(worktree.resolve("untracked.txt"));
        Files.createSymbolicLink(worktree.resolve("link"), root.resolve("README.md"));
        assertThrows(SecurityException.class, () -> verifier.verify(worktree, proposal));
    }
    @Test void snapshotCopiesApprovedContentWithoutTouchingSource() throws Exception {
        Path root = temp.resolve("project2");
        Files.createDirectory(root);
        git(root, "init");
        Files.writeString(root.resolve("README.md"), "old\n");
        git(root, "add", "README.md");
        git(root, "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "initial");
        Path worktree = temp.resolve("approved-worktree-" + root.getFileName());
        git(root, "worktree", "add", "--detach", worktree.toString());
        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        var change = new WorkspaceChangeService.Change("README.md",
                PatchDigest.sha256("old\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), "new\n");
        var proposal = new WorkspaceChangeService.Proposal(UUID.randomUUID().toString(), "sample",
                "APPLIED_IN_WORKTREE", "reviewer", PatchDigest.fingerprint("sample", List.of(change)),
                List.of(change), Instant.now());
        Files.writeString(worktree.resolve("README.md"), "new\n");
        Path copied;
        try (var snapshot = ApprovedWorktreeSnapshot.create(worktree, proposal, new ApprovedWorktreeIntegrityVerifier())) {
            copied = snapshot.path();
            assertEquals("new\n", Files.readString(copied.resolve("README.md")));
            assertFalse(Files.exists(copied.resolve(".git")));
        }
        assertFalse(Files.exists(copied));
        assertEquals("new\n", Files.readString(worktree.resolve("README.md")));
        assertEquals("old\n", Files.readString(root.resolve("README.md")));
    }
}
