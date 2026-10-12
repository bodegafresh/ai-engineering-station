package dev.aies.orchestration;
import dev.aies.domain.Task;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
public interface TaskRepository {
    Task create(String objective);
    Optional<Task> find(UUID id);
    /** Cancel a pending task atomically; running tasks require cooperative cancellation. */
    boolean cancel(UUID id);
    Optional<Claim> claimNext(Duration lease, int maxAttempts);
    boolean complete(Claim claim);
    boolean completeWithResult(Claim claim, String result, String model);
    Optional<TaskResult> findResult(UUID id);
    record TaskResult(UUID taskId, String model, String response, String completedAt) {}
    boolean fail(Claim claim, String reason);
    int recoverExpired(int maxAttempts);
    int publishOutbox(OutboxPublisher publisher, int limit);
    record Claim(Task task, String leaseToken, int attempt) {}
    @FunctionalInterface interface OutboxPublisher { void publish(String type, String payload); }
}
