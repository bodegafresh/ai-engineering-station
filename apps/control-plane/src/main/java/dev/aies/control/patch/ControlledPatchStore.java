package dev.aies.control.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Opt-in, bounded, single-file change requests. No shell execution. In-memory approvals expire. */
public final class ControlledPatchStore {
    public record Proposal(String id, String projectId, String path, String originalSha256,
                           String proposedSha256, int originalBytes, int proposedBytes,
                           Instant expiresAt, boolean approved, String status) {}
    private static final int MAX_BYTES = 32_768;
    private static final long TTL_SECONDS = 900;
    private static final Set<String> EXT = Set.of(".java", ".kt", ".kts", ".md", ".txt", ".yaml", ".yml", ".json", ".xml", ".properties", ".py", ".js", ".ts", ".tsx", ".go", ".rs", ".sql");
    private static final Set<String> BLOCKED = Set.of(".git", ".gradle", ".idea", ".aies", "build", "target", "dist", "node_modules", "secrets", ".venv");
    private final Path root;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private static final class Pending {
        final String id, project, relative, oldHash, newHash, content;
        final int oldSize, newSize;
        final Instant expires;
        boolean approved;
        String status = "PENDING";
        Pending(String id, String project, String relative, String oldHash, String newHash,
                String content, int oldSize, int newSize, Instant expires) {
            this.id=id; this.project=project; this.relative=relative; this.oldHash=oldHash;
            this.newHash=newHash; this.content=content; this.oldSize=oldSize;
            this.newSize=newSize; this.expires=expires;
        }
        Proposal view() { return new Proposal(id, project, relative, oldHash, newHash,
                oldSize, newSize, expires, approved, status); }
    }
    public ControlledPatchStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Invalid projects root");
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void validate(String project, String relative) {
        if (project == null || !project.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid project");
        if (relative == null || relative.length() > 240 || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid relative path");
        Path p = Path.of(relative);
        if (p.isAbsolute() || p.getNameCount() > 8) throw new IllegalArgumentException("Invalid relative path");
        for (Path segment : p) {
            String s = segment.toString().toLowerCase(Locale.ROOT);
            if (s.isBlank() || s.equals(".") || s.equals("..") || s.startsWith(".") || BLOCKED.contains(s))
                throw new IllegalArgumentException("Disallowed path");
        }
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.contains("secret") || name.contains("password") || name.contains("credential") ||
            EXT.stream().noneMatch(name::endsWith)) throw new IllegalArgumentException("Disallowed file");
    }
    private Path resolve(String project, String relative) throws IOException {
        validate(project, relative);
        Path projectDir = root.resolve(project);
        if (Files.isSymbolicLink(projectDir) || !Files.isDirectory(projectDir, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project missing");
        Path base = projectDir.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!base.startsWith(root) || base.equals(root)) throw new SecurityException("Outside projects root");
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))
            throw new IllegalArgumentException("File missing or not regular");
        Path parent = file.getParent();
        if (!parent.toRealPath().startsWith(base)) throw new SecurityException("Outside project");
        for (Path part = base; !part.equals(parent); ) {
            Path rel = base.relativize(parent);
            Path current = base;
            for (Path segment : rel) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) throw new SecurityException("Symlink directory denied");
            }
            break;
        }
        return file;
    }
    public synchronized Proposal prepare(String project, String relative, String replacement) throws IOException {
        if (replacement == null || replacement.isBlank()) throw new IllegalArgumentException("Empty replacement");
        byte[] proposed = replacement.getBytes(StandardCharsets.UTF_8);
        if (proposed.length > MAX_BYTES || replacement.indexOf('\0') >= 0) throw new IllegalArgumentException("Replacement too large or binary");
        Path target = resolve(project, relative);
        if (Files.size(target) > MAX_BYTES) throw new IllegalArgumentException("Original too large");
        byte[] original = Files.readAllBytes(target);
        if (original.length > MAX_BYTES || hash(original).equals(hash(proposed))) throw new IllegalArgumentException("No change or original too large");
        pending.entrySet().removeIf(e -> Instant.now().isAfter(e.getValue().expires));
        if (pending.size() >= 100) throw new IllegalStateException("Too many pending proposals");
        String id = UUID.randomUUID().toString();
        Pending p = new Pending(id, project, relative, hash(original), hash(proposed), replacement,
                original.length, proposed.length, Instant.now().plusSeconds(TTL_SECONDS));
        pending.put(id, p);
        return p.view();
    }
    private Pending get(String id) {
        Pending p = pending.get(id);
        if (p == null) throw new NoSuchElementException("Unknown proposal");
        if (Instant.now().isAfter(p.expires)) { pending.remove(id); throw new IllegalStateException("Proposal expired"); }
        return p;
    }
    public synchronized Proposal inspect(String id) { return get(id).view(); }
    public synchronized Proposal approve(String id, String expectedOriginalHash, String expectedProposedHash) {
        Pending p = get(id);
        if (!"PENDING".equals(p.status) || !p.oldHash.equals(expectedOriginalHash) || !p.newHash.equals(expectedProposedHash))
            throw new IllegalStateException("Approval mismatch");
        p.approved = true;
        p.status = "APPROVED";
        return p.view();
    }
    public synchronized Proposal apply(String id) throws IOException {
        Pending p = get(id);
        if (!p.approved || !"APPROVED".equals(p.status)) throw new SecurityException("Approval required");
        Path target = resolve(p.project, p.relative);
        if (!hash(Files.readAllBytes(target)).equals(p.oldHash)) throw new IllegalStateException("Original changed since proposal");
        Path tmp = Files.createTempFile(target.getParent(), ".aies-v020-", ".tmp");
        try {
            Files.writeString(tmp, p.content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            if (!hash(Files.readAllBytes(target)).equals(p.oldHash)) throw new IllegalStateException("Original changed during apply");
            // Atomic replace within the same directory; preserves no original permissions or metadata.
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            p.status = "APPLIED";
            return p.view();
        } finally { Files.deleteIfExists(tmp); }
    }
}
