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

/** Internal, opt-in durable task control. Does NOT execute tools or fabricate evidence. */
@Startup
@ApplicationScoped
public class ExecutionDispatchEventBus {
    public static final String CLAIM = "aies.execution.claim.v1";
    public static final String FAIL = "aies.execution.fail.v1";
    public static final String STATUS = "aies.execution.status.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.dispatch.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String root;
    @ConfigProperty(name="aies.execution.max-attempts", defaultValue="3") int maxAttempts;
    @ConfigProperty(name="aies.execution.lease-seconds", defaultValue="60") long leaseSeconds;
    private MessageConsumer<JsonObject> claimConsumer;
    private MessageConsumer<JsonObject> failConsumer;
    private MessageConsumer<JsonObject> statusConsumer;

    private ExecutionHandoffStore open() throws Exception {
        return new ExecutionHandoffStore(java.nio.file.Path.of(root).resolve("execution-handoff.sqlite"), mapper);
    }
    @PostConstruct void start() {
        if (!enabled) return;
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 10 || leaseSeconds > 3600)
            throw new IllegalStateException("Invalid execution dispatch policy");
        claimConsumer = vertx.eventBus().localConsumer(CLAIM, msg ->
            vertx.<JsonObject>executeBlocking(() -> {
                String workflowId = ExecutionDispatchPolicy.id(msg.body().getString("workflowId"));
                try (var store = open()) {
                    var work = store.claim(workflowId,maxAttempts,leaseSeconds);
                    return work.map(w -> JsonObject.mapFrom(w).put("claimed",true))
                        .orElseGet(() -> new JsonObject().put("claimed",false));
                }
            }, false).onSuccess(msg::reply).onFailure(e -> msg.fail(409,e.getClass().getSimpleName())));
        failConsumer = vertx.eventBus().localConsumer(FAIL, msg ->
            vertx.<JsonObject>executeBlocking(() -> {
                var body = msg.body();
                String workflowId = ExecutionDispatchPolicy.id(body.getString("workflowId"));
                String taskId = ExecutionDispatchPolicy.id(body.getString("taskId"));
                String token = ExecutionDispatchPolicy.token(body.getString("leaseToken"));
                boolean retryable = Boolean.TRUE.equals(body.getBoolean("retryable"));
                try (var store = open()) {
                    boolean changed = store.fail(workflowId,taskId,token,retryable,maxAttempts);
                    return new JsonObject().put("updated",changed);
                }
            }, false).onSuccess(msg::reply).onFailure(e -> msg.fail(409,e.getClass().getSimpleName())));
        statusConsumer = vertx.eventBus().localConsumer(STATUS, msg ->
            vertx.<JsonObject>executeBlocking(() -> {
                String workflowId = ExecutionDispatchPolicy.id(msg.body().getString("workflowId"));
                try (var store = open()) {
                    return new JsonObject().put("workflowId",workflowId)
                        .put("tasks",io.vertx.core.json.JsonArray.of(store.list(workflowId).toArray()));
                }
            }, false).onSuccess(msg::reply).onFailure(e -> msg.fail(409,e.getClass().getSimpleName())));
    }
    @PreDestroy void stop() {
        if (claimConsumer != null) claimConsumer.unregister();
        if (failConsumer != null) failConsumer.unregister();
        if (statusConsumer != null) statusConsumer.unregister();
    }
}
