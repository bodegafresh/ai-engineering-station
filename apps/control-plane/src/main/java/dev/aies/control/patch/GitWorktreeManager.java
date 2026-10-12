package dev.aies.control.patch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Creates detached worktrees. No model-provided shell command is ever executed. */
public final class GitWorktreeManager {
    private final Path sessions;
    public GitWorktreeManager(Path sessionsRoot) throws IOException {
        sessions=sessionsRoot.toAbsolutePath().normalize();
        Files.createDirectories(sessions);
        if(Files.isSymbolicLink(sessions) || !Files.isDirectory(sessions,LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Invalid sessions root");
    }
    public Path create(Path project,String id) throws Exception {
        if(!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Invalid session id");
        if(!run(project,"status","--porcelain").isBlank()) throw new IllegalStateException("Dirty source checkout");
        Path target=sessions.resolve(id);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Session exists");
        run(project,"worktree","add","--detach",target.toString(),"HEAD");
        return target;
    }
    public Path session(String id) {return sessions.resolve(id);}
    private static String run(Path project,String... args) throws Exception {
        List<String> command=new ArrayList<>(List.of("git","-C",project.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb=new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT","0");
        Process p=pb.start();
        if(!p.waitFor(20,TimeUnit.SECONDS)) {p.destroyForcibly();throw new IOException("Git timeout");}
        String output=new String(p.getInputStream().readNBytes(8192),StandardCharsets.UTF_8);
        if(p.exitValue()!=0) throw new IOException("Git operation failed (exit "+p.exitValue()+")");
        return output;
    }
}
