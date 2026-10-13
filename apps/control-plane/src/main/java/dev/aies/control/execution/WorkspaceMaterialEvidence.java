package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Validates on-disk documentation against the immutable artifact before audit. */
public final class WorkspaceMaterialEvidence {
    public static final String PREFIX = "workspace-doc-sha256:";
    private WorkspaceMaterialEvidence() {}

    public static String reference(String relativePath, String sha256) {
        if (relativePath == null || relativePath.isBlank() || relativePath.length() > 500 ||
            sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid material evidence");
        return PREFIX + relativePath + ":" + sha256;
    }

    public static void verify(Path workspaceRoot, CommunicationContracts.Artifact artifact) throws Exception {
        List<String> refs = artifact.sourceRefs();
        if (refs == null) throw new SecurityException("Missing source references");
        for (String ref : refs) {
            if (!ref.startsWith(PREFIX)) continue;
            String body = ref.substring(PREFIX.length());
            int split = body.lastIndexOf(':');
            if (split <= 0) throw new SecurityException("Malformed material reference");
            String relativeName = body.substring(0, split);
            String expected = body.substring(split + 1);
            if (!expected.matches("[a-f0-9]{64}")) throw new SecurityException("Malformed material hash");
            Path relative = Path.of(relativeName);
            if (relative.isAbsolute() || relative.normalize().startsWith("..") ||
                !relative.normalize().startsWith(Path.of(".aies", "generated-docs")) ||
                !relativeName.endsWith(".md")) throw new SecurityException("Unsafe material path");
            Path root = workspaceRoot.toRealPath();
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || Files.isSymbolicLink(file) || !Files.isRegularFile(file))
                throw new SecurityException("Missing or unsafe material file");
            Path real = file.toRealPath();
            if (!real.startsWith(root)) throw new SecurityException("Material escapes workspace");
            byte[] bytes = Files.readAllBytes(real);
            if (bytes.length > 262144) throw new SecurityException("Material too large");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII)))
                throw new SecurityException("Material SHA-256 mismatch");
            if (!new String(bytes, StandardCharsets.UTF_8).equals(artifact.content()))
                throw new SecurityException("Material content differs from canonical artifact");
        }
    }
}
