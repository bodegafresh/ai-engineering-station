package dev.aies.control.patch;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.stream.Stream;

/** Private, disposable, read-only-to-container snapshot of a verified Git worktree. */
public final class ApprovedWorktreeSnapshot implements AutoCloseable {
    private final Path directory;
    private ApprovedWorktreeSnapshot(Path directory) { this.directory = directory; }
    public Path path() { return directory; }

    public static ApprovedWorktreeSnapshot create(Path worktree,
            WorkspaceChangeService.Proposal proposal, ApprovedWorktreeIntegrityVerifier verifier) throws Exception {
        verifier.verify(worktree, proposal);
        Path snapshot = Files.createTempDirectory("aies-approved-snapshot-");
        try {
            Files.setPosixFilePermissions(snapshot, PosixFilePermissions.fromString("rwxr-xr-x"));
            try (Stream<Path> stream = Files.walk(worktree)) {
                for (Path source : stream.toList()) {
                    Path relative = worktree.relativize(source);
                    if (relative.toString().isEmpty() || relative.toString().equals(".git")) continue;
                    Path destination = snapshot.resolve(relative);
                    if (Files.isSymbolicLink(source)) throw new SecurityException("Snapshot symlink rejected");
                    if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createDirectories(destination);
                        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwxr-xr-x"));
                    } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                        // NOFOLLOW_LINKS ensures final-component symlinks are never opened.
                        try (var input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
                            Files.copy(input, destination);
                        }
                        Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rw-r--r--"));
                        if (!Arrays.equals(Files.readAllBytes(source), Files.readAllBytes(destination)))
                            throw new SecurityException("Snapshot source changed during copy");
                    } else throw new SecurityException("Unsupported snapshot entry");
                }
            }
            verifier.verify(worktree, proposal);
            for (var change : proposal.changes()) {
                Path file = snapshot.resolve(change.path());
                if (!Arrays.equals(ProjectFileAccess.replacement(change.replacement()), Files.readAllBytes(file)))
                    throw new SecurityException("Snapshot differs from approved content");
            }
            return new ApprovedWorktreeSnapshot(snapshot);
        } catch (Exception e) {
            deleteTree(snapshot);
            throw e;
        }
    }

    @Override public void close() throws IOException { deleteTree(directory); }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}
