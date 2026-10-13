package dev.aies.control.execution;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Non-blocking, process-local admission control for expensive specialist calls. */
public final class SpecialistAdmissionGate {
    private final Semaphore permits;
    public SpecialistAdmissionGate(int maxConcurrent) {
        if (maxConcurrent < 1 || maxConcurrent > 128) throw new IllegalArgumentException("Invalid concurrency limit");
        permits = new Semaphore(maxConcurrent, true);
    }
    /** Returns null immediately when saturated; never waits on the event loop. */
    public Lease tryAcquire() {
        return permits.tryAcquire() ? new Lease(permits) : null;
    }
    public int availablePermits() { return permits.availablePermits(); }
    public static final class Lease implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(Semaphore permits) { this.permits = permits; }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) permits.release();
        }
    }
}
