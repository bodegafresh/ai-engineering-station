package dev.aies.control.execution;

import dev.aies.control.patch.PatchDigest;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Runs a fixed, preapproved command in a network-disabled Docker container.
 * Requires a locally installed, pinned image; never accepts arbitrary shell from a model.
 */
public final class DockerVerificationRunner implements VerificationRunner {
    private static final int MAX_LOG = 65536;
    private final String image;
    private final int timeoutSeconds;
    public DockerVerificationRunner(String image, int timeoutSeconds) {
        if(image == null || !image.matches("[a-zA-Z0-9._/-]+@sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("Verification image must be digest pinned");
        if(timeoutSeconds < 5 || timeoutSeconds > 300) throw new IllegalArgumentException("Invalid timeout");
        this.image=image;
        this.timeoutSeconds=timeoutSeconds;
    }
    public VerificationEvidence verify(Path worktree, String profile) throws Exception {
        if(!"java-gradle".equals(profile)) throw new IllegalArgumentException("Unsupported verification profile");
        Path root=worktree.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
            throw new SecurityException("Invalid worktree");
        if(!Files.isRegularFile(root.resolve("gradlew"),LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root.resolve("gradlew")))
            throw new IllegalArgumentException("Gradle wrapper missing");
        // Run gradle through sh rather than requiring an executable bit on the checked-out wrapper.
        List<String> cmd=List.of("docker","run","--rm",
                "--network","none","--read-only","--cap-drop","ALL",
                "--security-opt","no-new-privileges","--pids-limit","128",
                "--memory","1536m","--cpus","1",
                "--user","65534:65534",
                "--mount","type=bind,src="+root+",dst=/workspace,readonly",
                "--tmpfs","/tmp:rw,noexec,nosuid,size=128m,uid=65534,gid=65534",
                "--tmpfs","/home/gradle:rw,nosuid,size=512m,uid=65534,gid=65534",
                "--tmpfs","/work:rw,nosuid,size=1024m,uid=65534,gid=65534",
                "--workdir","/work",
                "--env","GRADLE_USER_HOME=/home/gradle",
                "--env","HOME=/home/gradle",
                image, "sh", "-c",
                "cp -R /workspace/. /work/ && gradle test --offline --no-daemon --console=plain");
        ProcessBuilder pb=new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH","/usr/local/bin:/usr/bin:/bin");
        Process process=pb.start();
        var bytes=new ByteArrayOutputStream();
        Thread reader=Thread.ofVirtual().start(()->{
            try(InputStream in=process.getInputStream()) {
                byte[] buf=new byte[4096];int n;
                while((n=in.read(buf))!=-1) {
                    if(bytes.size()<MAX_LOG) bytes.write(buf,0,Math.min(n,MAX_LOG-bytes.size()));
                }
            }catch(IOException ignored){}
        });
        boolean finished=process.waitFor(timeoutSeconds,TimeUnit.SECONDS);
        if(!finished) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
        reader.join(5000);
        int code=finished?process.exitValue():-1;
        byte[] data=bytes.toByteArray();
        String excerpt=new String(data,StandardCharsets.UTF_8);
        return new VerificationEvidence(!finished?"TIMED_OUT":code==0?"PASSED":"FAILED",
                profile,code,!finished,PatchDigest.sha256(data),excerpt,Instant.now());
    }
}
