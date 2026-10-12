package dev.aies.control.execution;

import dev.aies.control.patch.PatchDigest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed-profile, network-isolated Docker executor. No user-supplied shell commands. */
public final class DockerVerificationRunner implements VerificationRunner {
    private static final int MAX_LOG = 65536;
    private final String image;
    private final int timeoutSeconds;
    private final VerificationProfileRegistry registry;
    public DockerVerificationRunner(String image, int timeoutSeconds) {
        this(image, timeoutSeconds, new VerificationProfileRegistry());
    }
    public DockerVerificationRunner(String image, int timeoutSeconds, VerificationProfileRegistry registry) {
        if (image == null || !image.matches("[a-zA-Z0-9._/-]+@sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Verification image must be digest pinned");
        if (timeoutSeconds < 5 || timeoutSeconds > 300) throw new IllegalArgumentException("Invalid timeout");
        this.image=image; this.timeoutSeconds=timeoutSeconds; this.registry=Objects.requireNonNull(registry);
    }
    public VerificationEvidence verify(Path worktree, String profile) throws Exception {
        var selected=registry.require(profile);
        Path root=worktree.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Invalid worktree");
        if (!".".equals(selected.marker()) && !Files.isRegularFile(root.resolve(selected.marker()), LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Required project manifest missing");
        // Preapproved shell fragment from a Java constant, never from a request or agent.
        String command="cp -R /workspace/. /work/ && " + selected.command();
        List<String> cmd=List.of("docker","run","--rm", "--network","none", "--read-only",
            "--cap-drop","ALL", "--security-opt","no-new-privileges", "--pids-limit","128",
            "--memory","1536m", "--cpus","1", "--user","65534:65534",
            "--mount","type=bind,src="+root+",dst=/workspace,readonly",
            "--tmpfs","/tmp:rw,noexec,nosuid,size=128m,uid=65534,gid=65534",
            "--tmpfs","/home/gradle:rw,nosuid,size=512m,uid=65534,gid=65534",
            "--tmpfs","/work:rw,nosuid,size=1024m,uid=65534,gid=65534",
            "--workdir","/work", "--env","GRADLE_USER_HOME=/home/gradle",
            "--env","HOME=/home/gradle", "--env","GOPROXY=off", "--env","GOSUMDB=off",
            "--env","PIP_NO_INDEX=1", "--env","npm_config_offline=true",
            image,"sh","-c",command);
        ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear(); pb.environment().put("PATH","/usr/local/bin:/usr/bin:/bin");
        Process process=pb.start();
        var bytes=new ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{
            try(InputStream in=process.getInputStream()) {
                byte[] buf=new byte[4096]; int n;
                while((n=in.read(buf))!=-1) {
                    synchronized(bytes) {if(bytes.size()<MAX_LOG) bytes.write(buf,0,Math.min(n,MAX_LOG-bytes.size()));}
                }
            } catch(IOException ignored) { }
        });
        boolean finished=process.waitFor(Math.min(timeoutSeconds,selected.timeoutSeconds()),TimeUnit.SECONDS);
        if(!finished) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        reader.join(5000);
        int code=finished?process.exitValue():-1;
        byte[] data; synchronized(bytes) {data=bytes.toByteArray();}
        String excerpt=new String(data,StandardCharsets.UTF_8);
        return new VerificationEvidence(!finished?"TIMED_OUT":code==0?"PASSED":"FAILED",
            profile,code,!finished,PatchDigest.sha256(data),excerpt,Instant.now());
    }
}
