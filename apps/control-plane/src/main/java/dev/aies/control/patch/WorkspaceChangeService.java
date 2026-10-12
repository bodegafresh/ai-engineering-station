package dev.aies.control.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Application service: coordinates proposal lifecycle, approval and isolated application. */
public final class WorkspaceChangeService {
    public record Change(String path,String originalSha256,String replacement) {}
    public record Proposal(String id,String projectId,String status,String reviewer,
                           String approvalSha256,List<Change> changes,Instant createdAt) {}
    private final ProjectFileAccess files;
    private final GitWorktreeManager worktrees;
    private final Map<String,Proposal> proposals=new HashMap<>();
    public WorkspaceChangeService(Path projectsRoot,Path sessionsRoot) throws IOException {
        files=new ProjectFileAccess(projectsRoot);
        worktrees=new GitWorktreeManager(sessionsRoot);
    }
    public static String sha(byte[] data) {return PatchDigest.sha256(data);}
    public synchronized Proposal prepare(String projectId,List<Change> changes) throws IOException {
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
        String id=UUID.randomUUID().toString();
        Proposal proposal=new Proposal(id,projectId,"PREPARED",null,
                PatchDigest.fingerprint(projectId,changes),List.copyOf(changes),Instant.now());
        proposals.put(id,proposal);
        return proposal;
    }
    public synchronized Proposal inspect(String id) {
        Proposal p=proposals.get(id);
        if(p==null) throw new NoSuchElementException("Unknown proposal");
        return p;
    }
    public synchronized Proposal approve(String id,String fingerprint,String reviewer) {
        Proposal p=inspect(id);
        if(!p.status().equals("PREPARED") || !p.approvalSha256().equals(fingerprint)
                || reviewer==null || !reviewer.matches("[A-Za-z0-9_-]{3,80}"))
            throw new SecurityException("Approval rejected");
        Proposal approved=transition(p,"APPROVED",reviewer);
        proposals.put(id,approved);
        return approved;
    }
    public synchronized Proposal apply(String id) throws Exception {
        Proposal p=inspect(id);
        if(!p.status().equals("APPROVED")) throw new SecurityException("Approval required");
        Path source=files.project(p.projectId());
        proposals.put(id,transition(p,"APPLYING",p.reviewer()));
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
            Proposal done=transition(p,"APPLIED_IN_WORKTREE",p.reviewer());
            proposals.put(id,done);
            return done;
        } catch(Exception e) {
            proposals.put(id,transition(p,"FAILED_REQUIRES_REVIEW",p.reviewer()));
            throw e;
        }
    }
    public Path sessionPath(String id) {
        Proposal p=inspect(id);
        if(!p.status().equals("APPLIED_IN_WORKTREE")) throw new IllegalStateException("Not applied");
        return worktrees.session(id);
    }
    private static Proposal transition(Proposal p,String state,String reviewer) {
        return new Proposal(p.id(),p.projectId(),state,reviewer,p.approvalSha256(),p.changes(),p.createdAt());
    }
}
