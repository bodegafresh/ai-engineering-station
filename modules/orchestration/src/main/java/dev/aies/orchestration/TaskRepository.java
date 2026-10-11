package dev.aies.orchestration;
import dev.aies.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface TaskRepository {
    Task create(String objective);
    Optional<Task> find(UUID id);
    boolean cancel(UUID id);
    /** Claim one ready task using an atomic SQLite write transaction. */
    Optional<Task> claimNext(int leaseSeconds);
    boolean complete(UUID id);
    boolean failOrRetry(UUID id, String reason, int maxAttempts, int backoffSeconds);
    int recoverExpired();
    /** Atomically claim unpublished outbox records; caller must acknowledge successful delivery. */
    List<OutboxEvent> pendingEvents(int limit);
    void acknowledgeEvent(long eventId);
    record OutboxEvent(long id, UUID taskId, String type) {}
}
