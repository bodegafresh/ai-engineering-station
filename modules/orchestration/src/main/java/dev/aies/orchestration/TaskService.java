package dev.aies.orchestration;
import dev.aies.domain.Task;
import java.util.Optional;
import java.util.UUID;
public final class TaskService {
    private final TaskRepository repository;
    public TaskService(TaskRepository repository) { this.repository = repository; }
    public Task submit(String objective) { return repository.create(objective); }
    public Optional<Task> find(UUID id) { return repository.find(id); }
    public boolean cancel(UUID id) { return repository.cancel(id); }
}
