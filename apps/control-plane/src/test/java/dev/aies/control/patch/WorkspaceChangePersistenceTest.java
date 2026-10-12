package dev.aies.control.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceChangePersistenceTest {
    @TempDir Path temp;
    @Test void proposalAndApprovalSurviveRestart() throws Exception {
        Path projects=Files.createDirectories(temp.resolve("projects"));
        Path project=Files.createDirectories(projects.resolve("sample"));
        Files.writeString(project.resolve("App.java"),"old");
        Path sessions=temp.resolve("sessions");
        String id;
        try(var service=new WorkspaceChangeService(projects,sessions)) {
            var c=new WorkspaceChangeService.Change("App.java",PatchDigest.sha256("old".getBytes(StandardCharsets.UTF_8)),"new");
            var p=service.prepare("sample",List.of(c));
            id=p.id();
            service.approve(id,p.approvalSha256(),"reviewer");
        }
        try(var service=new WorkspaceChangeService(projects,sessions)) {
            assertEquals("APPROVED",service.inspect(id).status());
            assertEquals("reviewer",service.inspect(id).reviewer());
        }
    }
    @Test void applyingStateRecoversAsManualReview() throws Exception {
        Path db=temp.resolve("journal.sqlite");
        String id;
        try(var store=new SqliteWorkspaceChangeRepository(db)) {
            var p=new WorkspaceChangeService.Proposal(java.util.UUID.randomUUID().toString(),
                    "sample","PREPARED",null,"fingerprint",List.of(),java.time.Instant.now());
            store.create(p);id=p.id();
            assertTrue(store.transition(id,"PREPARED","APPROVED","reviewer"));
            assertTrue(store.transition(id,"APPROVED","APPLYING","reviewer"));
        }
        try(var store=new SqliteWorkspaceChangeRepository(db)) {
            assertEquals("RECOVERY_REQUIRED",store.find(id).status());
            assertFalse(store.transition(id,"APPROVED","APPLYING","reviewer"));
        }
    }
}
