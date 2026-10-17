package dev.aies.control.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GovernedPublicationGateTest {
    @Test void serializesIndependentConnectionsWithinOneJvm() throws Exception {
        var root = Files.createTempDirectory("aies-publication-gate-");
        var database = root.resolve("policies.sqlite");
        var entered = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var secondEntered = new AtomicBoolean();
        Thread thread;
        try (var first = GovernedPublicationGate.acquire(database)) {
            thread = new Thread(() -> {
                try (var second = GovernedPublicationGate.acquire(database)) {
                    secondEntered.set(true);
                    entered.countDown();
                } catch (Exception e) { throw new RuntimeException(e); }
                finally { finished.countDown(); }
            });
            thread.start();
            assertFalse(entered.await(150, TimeUnit.MILLISECONDS), "Concurrent writer entered protected region");
            assertFalse(secondEntered.get());
        }
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertTrue(secondEntered.get());
    }
}
