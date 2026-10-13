package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceMaterialEvidenceTest {
    @TempDir Path root;
    private CommunicationContracts.Artifact artifact(String ref, String text) {
        return new CommunicationContracts.Artifact(CommunicationContracts.VERSION,"wf-1","task-1", "software-engineer",
            "execution-result", "text/markdown", text, List.of(ref));
    }
    @Test void verifiesActualFileAndRejectsTampering() throws Exception {
        Path docs = Files.createDirectories(root.resolve(".aies/generated-docs"));
        Path file = docs.resolve("result.md");
        Files.writeString(file,"# Valid\n");
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var a = artifact(WorkspaceMaterialEvidence.reference(".aies/generated-docs/result.md",hash),"# Valid\n");
        assertDoesNotThrow(() -> WorkspaceMaterialEvidence.verify(root,a));
        Files.writeString(file,"# Tampered\n");
        assertThrows(SecurityException.class, () -> WorkspaceMaterialEvidence.verify(root,a));
        Files.delete(file);
        assertThrows(SecurityException.class, () -> WorkspaceMaterialEvidence.verify(root,a));
    }
    @Test void rejectsTraversalAndSymlink() throws Exception {
        var ref = WorkspaceMaterialEvidence.reference("../outside.md", "a".repeat(64));
        assertThrows(SecurityException.class, () -> WorkspaceMaterialEvidence.verify(root,artifact(ref,"x")));
        Path docs = Files.createDirectories(root.resolve(".aies/generated-docs"));
        Path outside = Files.writeString(root.resolve("outside.md"),"x");
        Files.createSymbolicLink(docs.resolve("link.md"),outside);
        var linkRef = WorkspaceMaterialEvidence.reference(".aies/generated-docs/link.md", "a".repeat(64));
        assertThrows(SecurityException.class, () -> WorkspaceMaterialEvidence.verify(root,artifact(linkRef,"x")));
    }
}
