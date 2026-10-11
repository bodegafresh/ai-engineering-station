package dev.aies.control;
import dev.aies.orchestration.TaskService;
import dev.aies.sqlite.SqliteTaskRepository;
import io.quarkus.runtime.Startup;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Startup
@ApplicationScoped
public class TaskRuntime {
    private final SqliteTaskRepository repository;
    private final TaskService service;
    private final Vertx vertx;
    private final long timerId;
    public TaskRuntime(Vertx vertx, @ConfigProperty(name="aies.data.dir",defaultValue="./data") String dataDir) throws Exception {
        this.vertx=vertx;
        Files.createDirectories(Path.of(dataDir));
        repository = new SqliteTaskRepository(Path.of(dataDir,"tasks.db").toAbsolutePath().toString());
        service = new TaskService(repository, id -> vertx.eventBus().publish("task.created", id.toString()));
        vertx.eventBus().<String>consumer("task.created", msg -> {
            // SQLite JDBC is blocking: dispatch off the event loop.
            vertx.getDelegate().executeBlocking(() -> { repository.markCompleted(UUID.fromString(msg.body())); return null; }, false);
        });
        timerId = vertx.getDelegate().setPeriodic(3000, ignored ->
            vertx.getDelegate().executeBlocking(() -> { repository.processPending(25); return null; }, false));
    }
    public TaskService service() { return service; }
    @PreDestroy void close() { vertx.getDelegate().cancelTimer(timerId); }
}
