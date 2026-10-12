package dev.aies.sqlite;

import dev.aies.domain.TaskStatus;
import dev.aies.orchestration.TaskRepository.Claim;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteTaskRepositoryTest {
    @TempDir Path temp;
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test void survivesRestartAndPublishesOutbox() {
        String db = temp.resolve("tasks.db").toString();
        var first = new SqliteTaskRepository(db);
        var task = first.create("Persist and recover");
        assertEquals(TaskStatus.PENDING, first.find(task.id()).orElseThrow().status());
        var second = new SqliteTaskRepository(db);
        Claim claim = second.claimNext(LEASE, 2).orElseThrow();
        assertEquals(TaskStatus.RUNNING, claim.task().status());
        assertTrue(second.complete(claim));
        assertEquals(TaskStatus.COMPLETED, second.find(task.id()).orElseThrow().status());
        assertFalse(second.complete(claim));
        List<String> events = new ArrayList<>();
        assertEquals(3, second.publishOutbox((type, payload) -> events.add(type + ":" + payload), 10));
        assertEquals(List.of("task.created", "task.started", "task.completed"),
                events.stream().map(e -> e.substring(0, e.indexOf(':'))).toList());
        assertEquals(0, second.publishOutbox((type, payload) -> fail("Already delivered"), 10));
    }

    @Test void cancelPreventsClaim() {
        var repository = new SqliteTaskRepository(temp.resolve("cancel.db").toString());
        var task = repository.create("Cancel me");
        assertTrue(repository.cancel(task.id()));
        assertFalse(repository.cancel(task.id()));
        assertEquals(TaskStatus.CANCELLED, repository.find(task.id()).orElseThrow().status());
        assertTrue(repository.claimNext(LEASE, 2).isEmpty());
    }

    @Test void expiredLeaseCanRetryThenExhaustAttempts() throws InterruptedException {
        var repository = new SqliteTaskRepository(temp.resolve("retry.db").toString());
        var task = repository.create("Retry me");
        Claim first = repository.claimNext(Duration.ofMillis(100), 2).orElseThrow();
        Thread.sleep(180);
        assertEquals(1, repository.recoverExpired(2));
        assertEquals(TaskStatus.PENDING, repository.find(task.id()).orElseThrow().status());
        assertFalse(repository.complete(first));
        Claim second = repository.claimNext(Duration.ofMillis(100), 2).orElseThrow();
        assertEquals(2, second.attempt());
        Thread.sleep(180);
        assertEquals(1, repository.recoverExpired(2));
        assertEquals(TaskStatus.FAILED, repository.find(task.id()).orElseThrow().status());
        assertTrue(repository.claimNext(LEASE, 2).isEmpty());
    }

    @Test void storesLocalModelResultAndRejectsStaleCompletion() {
        var repository = new SqliteTaskRepository(temp.resolve("model.db").toString());
        var task = repository.create("Generate Java code");
        Claim claim = repository.claimNext(LEASE, 2).orElseThrow();
        assertTrue(repository.completeWithResult(claim, "public class Example {}", "qwen2.5-coder:7b"));
        var result = repository.findResult(task.id()).orElseThrow();
        assertEquals("qwen2.5-coder:7b", result.model());
        assertEquals("public class Example {}", result.response());
        assertFalse(repository.completeWithResult(claim, "stale", "qwen2.5-coder:7b"));
    }

    @Test void failedPublicationLeavesEventForRetry() {
        var repository = new SqliteTaskRepository(temp.resolve("outbox.db").toString());
        repository.create("Publish later");
        assertThrows(IllegalStateException.class,
                () -> repository.publishOutbox((type, payload) -> { throw new RuntimeException("offline"); }, 10));
        assertEquals(1, repository.publishOutbox((type, payload) -> {}, 10));
        assertEquals(0, repository.publishOutbox((type, payload) -> {}, 10));
    }
}
