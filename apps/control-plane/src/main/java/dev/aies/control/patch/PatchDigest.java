package dev.aies.control.patch;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class PatchDigest {
    private PatchDigest() {}
    public static String sha256(byte[] data) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
        catch(Exception e) {throw new IllegalStateException(e);}
    }
    public static String fingerprint(String projectId,List<WorkspaceChangeService.Change> changes) {
        StringBuilder b=new StringBuilder(projectId);
        for(var change:changes)
            b.append('|').append(change.path()).append('|').append(change.originalSha256())
             .append('|').append(sha256(ProjectFileAccess.replacement(change.replacement())));
        return sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
}
