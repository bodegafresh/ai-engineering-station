package dev.aies.control.patch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Fail-closed validation of the approved Git snapshot before sandbox execution. */
public final class ApprovedWorktreeIntegrityVerifier {
    private static final int MAX_GIT_OUTPUT = 2_000_000;

    public void verify(Path worktree, WorkspaceChangeService.Proposal proposal) throws Exception {
        Objects.requireNonNull(proposal, "proposal");
        if (!"APPLIED_IN_WORKTREE".equals(proposal.status())) throw new SecurityException("Proposal not applied");
        if (!PatchDigest.fingerprint(proposal.projectId(), proposal.changes()).equals(proposal.approvalSha256()))
            throw new SecurityException("Approval fingerprint mismatch");
        Path root = worktree.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Invalid worktree");

        Set<String> tracked = parseNullTerminated(git(root, "ls-files", "--cached", "-z"));
        if (tracked.isEmpty()) throw new SecurityException("Empty Git index");
        Map<String, WorkspaceChangeService.Change> approved = new HashMap<>();
        for (var change : proposal.changes()) {
            if (approved.putIfAbsent(change.path(), change) != null || !tracked.contains(change.path()))
                throw new SecurityException("Untracked or duplicate approved path");
        }

        // Status -z emits two status bytes, a space, then path; rename/copy statuses are forbidden.
        byte[] status = git(root, "status", "--porcelain=v1", "--untracked-files=all", "-z");
        Set<String> observed = new HashSet<>();
        for (String record : parseNullTerminated(status)) {
            if (record.length() < 4 || !record.startsWith(" M "))
                throw new SecurityException("Unexpected Git change");
            String path = record.substring(3);
            if (!approved.containsKey(path) || !observed.add(path))
                throw new SecurityException("Unapproved Git change");
        }
        if (!observed.equals(approved.keySet())) throw new SecurityException("Approved changes differ from worktree");

        // Git ignores do not make extra files safe: inspect every filesystem entry.
        try (Stream<Path> entries = Files.walk(root)) {
            for (Path entry : entries.toList()) {
                if (entry.equals(root)) continue;
                String relative = root.relativize(entry).toString().replace('\\', '/');
                if (relative.equals(".git")) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry))
                        throw new SecurityException("Invalid worktree metadata");
                    continue;
                }
                if (Files.isSymbolicLink(entry)) throw new SecurityException("Symlink in worktree");
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || !tracked.contains(relative))
                    throw new SecurityException("Unexpected file in worktree");
            }
        }
        for (String path : tracked) {
            Path file = root.resolve(path).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) throw new SecurityException("Tracked file missing or unsafe");
        }
        for (var change : proposal.changes()) {
            Path file = root.resolve(change.path()).normalize();
            if (!file.startsWith(root)) throw new SecurityException("Invalid approved path");
            byte[] expected = ProjectFileAccess.replacement(change.replacement());
            byte[] actual = Files.readAllBytes(file);
            if (!Arrays.equals(expected, actual)) throw new SecurityException("Approved content changed");
        }
    }

    private static Set<String> parseNullTerminated(byte[] data) {
        Set<String> values = new LinkedHashSet<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                if (i > start) values.add(new String(data, start, i - start, StandardCharsets.UTF_8));
                start = i + 1;
            }
        }
        if (start != data.length) throw new SecurityException("Malformed Git output");
        return values;
    }

    private static byte[] git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        byte[] output;
        try (var stream = process.getInputStream(); var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = stream.read(buffer)) >= 0) {
                if (bytes.size() + n > MAX_GIT_OUTPUT) throw new SecurityException("Git output too large");
                bytes.write(buffer, 0, n);
            }
            output = bytes.toByteArray();
        } catch (Exception e) {
            process.destroyForcibly();
            throw e;
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Git inspection timeout");
        }
        if (process.exitValue() != 0) throw new SecurityException("Git inspection failed");
        return output;
    }
}
