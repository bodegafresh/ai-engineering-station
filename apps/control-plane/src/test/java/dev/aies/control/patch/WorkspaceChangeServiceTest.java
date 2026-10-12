package dev.aies.control.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceChangeServiceTest {
    @TempDir Path temp;
    @Test void prepareApproveAndDenyTraversal() throws Exception {
        Path projects=Files.createDirectories(temp.resolve("projects"));
        Path project=Files.createDirectories(projects.resolve("sample"));
        Files.writeString(project.resolve("App.java"),"old");
        var service=new WorkspaceChangeService(projects,temp.resolve("sessions"));
        var change=new WorkspaceChangeService.Change("App.java",
                PatchDigest.sha256("old".getBytes(StandardCharsets.UTF_8)),"new");
        var proposal=service.prepare("sample",List.of(change));
        assertEquals("PREPARED",proposal.status());
        assertThrows(SecurityException.class,()->service.approve(proposal.id(),"bad","reviewer"));
        assertEquals("APPROVED",service.approve(proposal.id(),proposal.approvalSha256(),"reviewer").status());
        assertThrows(SecurityException.class,()->service.prepare("sample",List.of(
                new WorkspaceChangeService.Change("../outside.java",change.originalSha256(),"new"))));
        assertEquals("old",Files.readString(project.resolve("App.java")));
    }
    @Test void applyChangesOnlyDetachedWorktree() throws Exception {
        Path projects=Files.createDirectories(temp.resolve("projects"));
        Path project=Files.createDirectories(projects.resolve("sample"));
        Files.writeString(project.resolve("App.java"),"old");
        Process init=new ProcessBuilder("git","init",project.toString()).start();
        assertEquals(0,init.waitFor());
        run(project,"git","add","App.java");
        run(project,"git","-c","user.name=AIES","-c","user.email=aies@example.test",
                "commit","-m","initial");
        var service=new WorkspaceChangeService(projects,temp.resolve("sessions"));
        var change=new WorkspaceChangeService.Change("App.java",PatchDigest.sha256("old".getBytes(StandardCharsets.UTF_8)),"new");
        var p=service.prepare("sample",List.of(change));
        service.approve(p.id(),p.approvalSha256(),"reviewer");
        assertEquals("APPLIED_IN_WORKTREE",service.apply(p.id()).status());
        assertEquals("old",Files.readString(project.resolve("App.java")));
        assertEquals("new",Files.readString(service.sessionPath(p.id()).resolve("App.java")));
        assertThrows(SecurityException.class,()->service.apply(p.id()));
    }
    private static void run(Path directory,String... command) throws Exception {
        Process p=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output=new String(p.getInputStream().readAllBytes());
        assertEquals(0,p.waitFor(),output);
    }
}
