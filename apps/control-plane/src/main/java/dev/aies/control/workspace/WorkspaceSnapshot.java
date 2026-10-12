package dev.aies.control.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

/** Bounded, read-only repository snapshot. Does not follow symbolic links. */
public final class WorkspaceSnapshot {
    public record Snapshot(String context, List<String> files, boolean truncated) {}
    private static final Set<String> EXTENSIONS = Set.of(".java", ".kt", ".kts", ".md", ".txt", ".yaml", ".yml", ".json", ".xml", ".properties", ".gradle", ".py", ".js", ".ts", ".tsx", ".go", ".rs", ".sql");
    private static final Set<String> EXCLUDED = Set.of(".git", ".gradle", ".idea", ".venv", "venv", "node_modules", "build", "target", "dist", "out", ".next", "__pycache__", ".aies", "secrets");
    private static final int MAX_FILES = 24;
    private static final int MAX_FILE_BYTES = 8_192;
    private static final int MAX_CONTEXT_CHARS = 24_000;
    private static final int MAX_DEPTH = 7;

    private WorkspaceSnapshot() {}

    public static Snapshot read(Path configuredRoot, String projectId) throws IOException {
        if (projectId == null || !projectId.matches("[a-zA-Z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid project ID");
        Path root = configuredRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IllegalArgumentException("Projects root must be a real directory");
        Path rootReal = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Path project = rootReal.resolve(projectId);
        if (!Files.isDirectory(project, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(project))
            throw new IllegalArgumentException("Project directory missing or symbolic link");
        Path projectReal = project.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!projectReal.startsWith(rootReal) || projectReal.equals(rootReal))
            throw new IllegalArgumentException("Project outside root");

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(projectReal, MAX_DEPTH)) {
            paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                 .filter(p -> !Files.isSymbolicLink(p))
                 .filter(p -> allowed(projectReal.relativize(p)))
                 .sorted(Comparator.comparing(p -> projectReal.relativize(p).toString()))
                 .limit(MAX_FILES + 1L)
                 .forEach(candidates::add);
        }
        StringBuilder context = new StringBuilder();
        List<String> included = new ArrayList<>();
        boolean truncated = candidates.size() > MAX_FILES;
        for (Path file : candidates.subList(0, Math.min(MAX_FILES, candidates.size()))) {
            if (Files.size(file) > MAX_FILE_BYTES) { truncated = true; continue; }
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES || hasBinaryBytes(bytes)) { truncated = true; continue; }
            String content = new String(bytes, StandardCharsets.UTF_8);
            String relative = projectReal.relativize(file).toString().replace('\\', '/');
            String block = "\n--- FILE: " + relative + " (untrusted repository data) ---\n" + content + "\n--- END FILE ---\n";
            if (context.length() + block.length() > MAX_CONTEXT_CHARS) { truncated = true; break; }
            context.append(block);
            included.add(relative);
        }
        return new Snapshot(context.toString(), List.copyOf(included), truncated);
    }

    private static boolean allowed(Path relative) {
        for (Path component : relative) {
            String part = component.toString().toLowerCase(Locale.ROOT);
            if (EXCLUDED.contains(part) || part.startsWith(".")) return false;
        }
        String name = relative.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals(".env") || name.startsWith(".env.") || name.contains("secret") || name.contains("credential") || name.contains("password") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".keystore")) return false;
        return EXTENSIONS.stream().anyMatch(name::endsWith) || name.equals("dockerfile") || name.equals("makefile");
    }

    private static boolean hasBinaryBytes(byte[] bytes) {
        for (byte b : bytes) if (b == 0) return true;
        return false;
    }
}
