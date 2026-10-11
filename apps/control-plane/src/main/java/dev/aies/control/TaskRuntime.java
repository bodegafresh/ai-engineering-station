package dev.aies.control;

import dev.aies.orchestration.TaskService;
import dev.aies.sqlite.SqliteTaskRepository;
import io.quarkus.runtime.Startup;
import io.vertx.mutiny.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** The event bus is a wake-up signal; SQLite remains authoritative. */
@Startup
@ApplicationScoped
public class TaskRuntime {
    private static final Logger LOG=Logger.getLogger(TaskRuntime.class);
    private final SqliteTaskRepository repository;
    private final TaskService service;
    private final Vertx vertx;
    private final long timerId;
    private final AtomicBoolean busy=new AtomicBoolean();
    public TaskRuntime(Vertx vertx,@ConfigProperty(name="aies.data.dir",defaultValue="./data") String dataDir) throws Exception {
        this.vertx=vertx;
        Files.createDirectories(Path.of(dataDir));
        repository=new SqliteTaskRepository(Path.of(dataDir,"tasks.db").toAbsolutePath().toString());
        service=new TaskService(repository);
        vertx.eventBus().<String>consumer("task.wakeup", ignored -> schedule());
        timerId=vertx.getDelegate().setPeriodic(1000, ignored -> schedule());
        schedule();
    }
    public TaskService service(){return service;}
    public void wakeup(){vertx.eventBus().publish("task.wakeup","new-task");}
    private void schedule(){
        if(!busy.compareAndSet(false,true))return;
        vertx.getDelegate().executeBlocking(() -> {
            try {
                repository.recoverExpired();
                // POC handler: real agents will replace this no-op completion step.
                for(int i=0;i<10;i++) {
                    var task=repository.claimNext(30);
                    if(task.isEmpty())break;
                    try { repository.complete(task.get().id()); }
                    catch(Exception e) { repository.failOrRetry(task.get().id(),e.toString(),3,2); }
                }
                for(var event:repository.pendingEvents(50)) {
                    vertx.eventBus().publish("task.events",event.type()+":"+event.taskId());
                    repository.acknowledgeEvent(event.id());
                }
            }catch(Exception e){LOG.error("Task reconciliation failed",e);}
            finally{busy.set(false);}
            return null;
        },false);
    }
    @PreDestroy void close(){vertx.getDelegate().cancelTimer(timerId);}
}
