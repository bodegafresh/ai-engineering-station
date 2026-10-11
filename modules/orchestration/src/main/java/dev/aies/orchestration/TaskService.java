package dev.aies.orchestration;
import dev.aies.domain.Task;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
public final class TaskService {
    private final TaskRepository repository;
    private final Consumer<UUID> notifier;
    public TaskService(TaskRepository repository, Consumer<UUID> notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }
    public Task submit(String objective) {
        Task task = repository.create(objective);
        notifier.accept(task.id()); // best-effort notification; DB polling guarantees recovery
        return task;
    }
    public Optional<Task> find(UUID id) { return repository.find(id); }
}
