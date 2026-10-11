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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-node durable scheduler. Never runs model HTTP on an event-loop thread. */
@Startup
@ApplicationScoped
public class TaskRuntime {
    private static final Logger LOG=Logger.getLogger(TaskRuntime.class);
    private final SqliteTaskRepository repository;
    private final TaskService service;
    private final Vertx vertx;
    private final LocalModelClient model;
    private final long timerId;
    private final AtomicBoolean polling=new AtomicBoolean(false);
    private final int maxAttempts;
    private final Duration lease;
    private final boolean enabled;

    public TaskRuntime(Vertx vertx, LocalModelClient model,
       @ConfigProperty(name="aies.data.dir",defaultValue="./data") String dataDir,
       @ConfigProperty(name="aies.scheduler.max-attempts",defaultValue="3") int maxAttempts,
       @ConfigProperty(name="aies.scheduler.lease-seconds",defaultValue="180") long leaseSeconds,
       @ConfigProperty(name="aies.model.timeout-seconds",defaultValue="90") long modelTimeoutSeconds,
       @ConfigProperty(name="aies.model.enabled",defaultValue="false") boolean enabled) throws Exception {
        if(leaseSeconds<=modelTimeoutSeconds+10) throw new IllegalArgumentException("Lease must exceed model timeout by >10 seconds");
        this.vertx=vertx;this.model=model;this.maxAttempts=maxAttempts;
        this.lease=Duration.ofSeconds(leaseSeconds);this.enabled=enabled;
        Files.createDirectories(Path.of(dataDir));
        repository=new SqliteTaskRepository(Path.of(dataDir,"tasks.db").toAbsolutePath().toString());
        service=new TaskService(repository);
        vertx.eventBus().consumer("task.wakeup",msg->schedulePoll());
        timerId=vertx.getDelegate().setPeriodic(2000,ignored->schedulePoll());
        schedulePoll();
        LOG.infof("Local model execution %s",enabled?"ENABLED":"DISABLED (tasks remain pending)");
    }
    public TaskService service() { return service; }
    public SqliteTaskRepository repository() { return repository; }
    private void schedulePoll() {
        if(!polling.compareAndSet(false,true)) return;
        vertx.getDelegate().<Void>executeBlocking(() -> {
            repository.recoverExpired(maxAttempts);
            repository.publishOutbox((type,payload)->vertx.eventBus().publish("aies.events",type+":"+payload),50);
            if(enabled) {
                var claim=repository.claimNext(lease,maxAttempts);
                if(claim.isPresent()) {
                    var work=claim.get();
                    try {
                        var response=model.complete(work.task().objective());
                        if(!repository.completeWithResult(work,response.content(),response.model()))
                            LOG.warnf("Stale result discarded for task %s",work.task().id());
                    } catch (Exception e) {
                        LOG.warnf("Model attempt %d failed for task %s: %s",work.attempt(),work.task().id(),e.toString());
                        // Failure is terminal for this attempt; explicit future retry policy will be added later.
                        repository.fail(work,e.getClass().getSimpleName()+": "+e.getMessage());
                    }
                }
            }
            return null;
        },false).onComplete(ar->{
            polling.set(false);
            if(ar.failed()) LOG.error("Scheduler iteration failed; next poll will retry",ar.cause());
        });
    }
    @PreDestroy void close() { vertx.getDelegate().cancelTimer(timerId); }
}
