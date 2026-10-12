package dev.aies.control.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Application service for human-reviewed changes. Never modifies the source checkout. */
public final class WorkspaceChangeService implements AutoCloseable {
    public record Change(String path,String originalSha256,String replacement) {}
    public record Proposal(String id,String projectId,String status,String reviewer,
                           String approvalSha256,List<Change> changes,Instant createdAt) {}
    private final ProjectFileAccess files;
    private final GitWorktreeManager worktrees;
    private final SqliteWorkspaceChangeRepository repository;

    public WorkspaceChangeService(Path projectsRoot,Path sessionsRoot) throws Exception {
        files=new ProjectFileAccess(projectsRoot);
        worktrees=new GitWorktreeManager(sessionsRoot);
        repository=new SqliteWorkspaceChangeRepository(sessionsRoot.resolve("workspace-changes.sqlite"));
    }
    public static String sha(byte[] data) {return PatchDigest.sha256(data);}
    public synchronized Proposal prepare(String projectId,List<Change> changes) throws Exception {
        Path root=files.project(projectId);
        if(changes==null || changes.isEmpty() || changes.size()>12) throw new IllegalArgumentException("Invalid changes");
        Set<String> names=new HashSet<>();
        for(Change c:changes) {
            if(c==null || c.path()==null || !names.add(c.path())) throw new IllegalArgumentException("Duplicate or invalid change");
            byte[] next=ProjectFileAccess.replacement(c.replacement());
            byte[] original=files.read(files.file(root,c.path()));
            if(Arrays.equals(original,next) || !sha(original).equals(c.originalSha256()))
                throw new IllegalStateException("Baseline mismatch or no change");
        }
        Proposal p=new Proposal(UUID.randomUUID().toString(),projectId,"PREPARED",null,
                PatchDigest.fingerprint(projectId,changes),List.copyOf(changes),Instant.now());
        repository.create(p);
        return p;
    }
    public synchronized Proposal inspect(String id) throws Exception {return repository.find(id);}
    public synchronized Proposal approve(String id,String fingerprint,String reviewer) throws Exception {
        Proposal p=inspect(id);
        if(!p.status().equals("PREPARED") || !p.approvalSha256().equals(fingerprint)
                || reviewer==null || !reviewer.matches("[A-Za-z0-9_-]{3,80}"))
            throw new SecurityException("Approval rejected");
        if(!repository.transition(id,"PREPARED","APPROVED",reviewer))
            throw new IllegalStateException("Concurrent approval");
        return inspect(id);
    }
    public synchronized Proposal apply(String id) throws Exception {
        Proposal p=inspect(id);
        if(!p.status().equals("APPROVED")) throw new SecurityException("Approval required");
        Path source=files.project(p.projectId());
        if(!repository.transition(id,"APPROVED","APPLYING",p.reviewer()))
            throw new IllegalStateException("Concurrent application");
        try {
            Path worktree=worktrees.create(source,p.id());
            for(Change c:p.changes()) {
                Path f=files.file(worktree,c.path());
                if(!sha(files.read(f)).equals(c.originalSha256()))
                    throw new IllegalStateException("Worktree baseline mismatch");
            }
            for(Change c:p.changes()) {
                Path f=files.file(worktree,c.path());
                Path temp=Files.createTempFile(f.getParent(),".aies-",".tmp");
                try {
                    Files.writeString(temp,c.replacement(),StandardCharsets.UTF_8);
                    Files.move(temp,f,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                } finally {Files.deleteIfExists(temp);}
            }
            if(!repository.transition(id,"APPLYING","APPLIED_IN_WORKTREE",p.reviewer()))
                throw new IllegalStateException("Concurrent completion");
            return inspect(id);
        } catch(Exception e) {
            repository.transition(id,"APPLYING","RECOVERY_REQUIRED",p.reviewer());
            throw e;
        }
    }
    public Path sessionPath(String id) throws Exception {
        Proposal p=inspect(id);
        if(!p.status().equals("APPLIED_IN_WORKTREE")) throw new IllegalStateException("Not applied");
        return worktrees.session(id);
    }
    @Override public void close() throws Exception {repository.close();}
}
