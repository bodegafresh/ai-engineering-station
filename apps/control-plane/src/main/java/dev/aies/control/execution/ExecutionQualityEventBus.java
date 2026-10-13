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

/** Opt-in internal quality gate. No REST endpoint and no automatic approval. */
@Startup @ApplicationScoped
public class ExecutionQualityEventBus {
    public static final String REVIEW = "aies.execution.quality.review.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.quality.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String root;
    @ConfigProperty(name="aies.execution.quality.timeout-seconds", defaultValue="90") int timeoutSeconds;
    private MessageConsumer<JsonObject> consumer;
    @PostConstruct void start() {
        if (!enabled) return;
        var gate = new ExecutionQualityGate(vertx, mapper, java.nio.file.Path.of(root), timeoutSeconds);
        consumer = vertx.eventBus().localConsumer(REVIEW, msg ->
            vertx.<JsonObject>executeBlocking(() -> gate.review(msg.body().getString("workflowId"),
                msg.body().getString("taskId")), false)
                .onSuccess(msg::reply).onFailure(e -> msg.fail(409, e.getClass().getSimpleName())));
    }
    @PreDestroy void stop() { if (consumer != null) consumer.unregister(); }
}
