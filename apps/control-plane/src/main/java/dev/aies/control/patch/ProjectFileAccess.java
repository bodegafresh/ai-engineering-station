package dev.aies.control.patch;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Validates paths and reads bounded files without following final-component links. */
public final class ProjectFileAccess {
    public static final int MAX_BYTES = 32768;
    private static final Set<String> BLOCKED = Set.of("build","target","node_modules","secrets","dist","out",".git",".aies");
    private final Path projectsRoot;
    public ProjectFileAccess(Path projectsRoot) throws IOException {
        Path root = projectsRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Invalid projects root");
        this.projectsRoot = root.toRealPath();
    }
    public Path project(String id) throws IOException {
        if(id == null || !id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Invalid project");
        Path p = projectsRoot.resolve(id);
        if(Files.isSymbolicLink(p) || !Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Project missing");
        Path real = p.toRealPath();
        if(!real.startsWith(projectsRoot) || real.equals(projectsRoot)) throw new SecurityException("Outside root");
        return real;
    }
    public Path file(Path project, String relative) throws IOException {
        if(relative == null || relative.isBlank() || relative.length()>240 || relative.indexOf('\\')>=0
                || relative.indexOf('\0')>=0 || relative.startsWith("/"))
            throw new IllegalArgumentException("Invalid path");
        Path rel=Path.of(relative);
        if(rel.isAbsolute() || rel.getNameCount()>10) throw new IllegalArgumentException("Invalid path");
        Path cursor=project;
        for(Path part:rel) {
            String name=part.toString().toLowerCase(Locale.ROOT);
            if(name.isBlank() || name.equals(".") || name.equals("..") || name.startsWith(".")
                    || BLOCKED.contains(name) || name.contains("secret") || name.contains("password")
                    || name.contains("credential")) throw new SecurityException("Denied path");
            cursor=cursor.resolve(part);
            if(Files.isSymbolicLink(cursor)) throw new SecurityException("Symbolic link denied");
        }
        Path result=cursor.normalize();
        if(!result.startsWith(project) || !Files.isRegularFile(result,LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("File missing");
        return result;
    }
    public byte[] read(Path path) throws IOException {
        try(var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS))) {
            var bytes=new java.io.ByteArrayOutputStream();
            ByteBuffer buffer=ByteBuffer.allocate(8192);
            int n;
            while((n=channel.read(buffer))!=-1) {
                if(n==0) continue;
                buffer.flip();
                bytes.write(buffer.array(),0,buffer.remaining());
                buffer.clear();
                if(bytes.size()>MAX_BYTES) throw new IllegalArgumentException("File too large");
            }
            return bytes.toByteArray();
        }
    }
    public static byte[] replacement(String content) {
        if(content==null || content.indexOf('\0')>=0) throw new IllegalArgumentException("Invalid replacement");
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("Replacement too large");
        return bytes;
    }
}
