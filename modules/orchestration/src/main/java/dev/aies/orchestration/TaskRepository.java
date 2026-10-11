package dev.aies.orchestration;
import dev.aies.domain.Task;
import java.util.Optional;
import java.util.UUID;
public interface TaskRepository {
    Task create(String objective);
    Optional<Task> find(UUID id);
    boolean markCompleted(UUID id);
    int processPending(int limit);
}
