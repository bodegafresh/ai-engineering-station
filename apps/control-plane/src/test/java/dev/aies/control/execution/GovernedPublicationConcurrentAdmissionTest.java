package dev.aies.control.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded two-connection contention: this is NOT a cross-JVM filesystem fencing proof. */
class GovernedPublicationConcurrentAdmissionTest {
    @TempDir Path temp;

    @Test void competingIndependentConnectionsCannotBothAdmitSameAttempt() throws Exception {
        Path file = temp.resolve("policies.sqlite");
        var policy = new GovernedToolResourcePolicy.Snapshot("wf", "task", "attempt", "engineer", "1.0", 7,
            Instant.parse("2099-01-01T00:00:00Z"), false,
            List.of(new GovernedToolResourcePolicy.Grant("workspace-documentation", "invoke-model", "ollama:software-engineer")));
        var request = new GovernedToolResourcePolicy.Request("wf", "task", "attempt", "engineer", "1.0",
            "workspace-documentation", "invoke-model", "ollama:software-engineer");
        try (var initial = new GovernedToolResourcePolicyStore(file)) { initial.approve(policy); }
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                start.await();
                try (var store = new GovernedToolResourcePolicyStore(file)) {
                    try {
                        store.admitPublication(request);
                        return true;
                    } catch (SecurityException denied) {
                        return false;
                    }
                }
            };
            var a = workers.submit(task);
            var b = workers.submit(task);
            start.countDown();
            boolean first = a.get(15, TimeUnit.SECONDS);
            boolean second = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(first, second, "Exactly one competing admission must succeed");
        }
        try (var reopened = new GovernedToolResourcePolicyStore(file)) {
            assertTrue(reopened.publicationAdmitted("wf", "task", "attempt"));
            assertEquals(1, reopened.auditTrail("wf", "task", "attempt").stream()
                .filter(e -> "PUBLICATION_ADMISSION".equals(e.type()) && "ALLOW".equals(e.decision()))
                .count(), "Durable admission and ALLOW audit must be singular");
        }
    }
}
