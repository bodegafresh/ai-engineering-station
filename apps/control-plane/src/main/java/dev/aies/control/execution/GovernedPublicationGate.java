package dev.aies.control.execution;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cooperative single-host gate for governed publication and operator revocation.
 * All participating processes must use the same stable local lock-file inode.
 * No distributed fencing, hostile directory mutation protection or cross-store transaction.
 */
final class GovernedPublicationGate implements AutoCloseable {
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final ReentrantLock jvmLock;
    private final FileChannel channel;
    private final FileLock osLock;

    static GovernedPublicationGate acquire(Path policyDatabase) throws Exception {
        Path database = policyDatabase.toAbsolutePath().normalize();
        Path lockFile = database.resolveSibling(database.getFileName() + ".publication.lock");
        Files.createDirectories(lockFile.getParent());
        // Refuse symlinked lock directories and files. In particular, never follow a
        // substituted lock-file symlink to an unrelated inode or sensitive file.
        if (Files.isSymbolicLink(lockFile.getParent()))
            throw new SecurityException("Symlinked publication lock directory");
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock(true));
        local.lockInterruptibly();
        FileChannel channel = null;
        try {
            if (Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockFile)))
                throw new SecurityException("Unsafe publication lock file");
            channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS));
            if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("Publication lock file replaced");
            FileLock osLock = channel.lock();
            return new GovernedPublicationGate(local, channel, osLock);
        } catch (Exception | Error failure) {
            if (channel != null) try { channel.close(); } catch (Exception close) { failure.addSuppressed(close); }
            local.unlock();
            throw failure;
        }
    }

    private GovernedPublicationGate(ReentrantLock local, FileChannel channel, FileLock osLock) {
        this.jvmLock = local;
        this.channel = channel;
        this.osLock = osLock;
    }

    @Override public void close() throws Exception {
        try {
            try { osLock.release(); } finally { channel.close(); }
        } finally {
            jvmLock.unlock();
        }
    }
}
