package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Opt-in internal Event Bus entrypoint. Does not register a fake specialist. */
@Startup @ApplicationScoped
public class ExecutionWorkerEventBus {
    public static final String RUN_ONE="aies.execution.worker.run-one.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.worker.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String root;
    @ConfigProperty(name="aies.execution.max-attempts",defaultValue="3") int maxAttempts;
    @ConfigProperty(name="aies.execution.lease-seconds",defaultValue="60") long leaseSeconds;
    @ConfigProperty(name="aies.execution.worker.timeout-seconds",defaultValue="30") int timeoutSeconds;
    private MessageConsumer<JsonObject> consumer;
    @PostConstruct void start() {
        if(!enabled)return;
        var engine=new ExecutionWorkerEngine(vertx,mapper,java.nio.file.Path.of(root),maxAttempts,leaseSeconds,timeoutSeconds);
        consumer=vertx.eventBus().localConsumer(RUN_ONE,msg ->
            vertx.<JsonObject>executeBlocking(() -> engine.runOne(msg.body().getString("workflowId")),false)
                .onSuccess(msg::reply).onFailure(e -> msg.fail(409,e.getClass().getSimpleName())));
    }
    @PreDestroy void stop() {if(consumer!=null)consumer.unregister();}
}
