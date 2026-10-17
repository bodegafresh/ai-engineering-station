package dev.aies.control.execution;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cooperative host-local publication/revocation gate.
 * All governed filesystem publishers and operator revocations must participate.
 * The lock file must be on a filesystem supporting reliable OS advisory locks;
 * it is not a distributed fencing token or an atomic multi-store transaction.
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
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock(true));
        local.lockInterruptibly();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
