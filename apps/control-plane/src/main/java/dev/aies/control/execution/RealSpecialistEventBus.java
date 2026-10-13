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
import java.net.http.HttpClient;

/** Opt-in adapter to the existing worker address; does not create another agent registry. */
@Startup @ApplicationScoped
public class RealSpecialistEventBus {
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.real-specialist.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.model.base-url", defaultValue="http://127.0.0.1:11434") String baseUrl;
    @ConfigProperty(name="aies.model.name", defaultValue="qwen3:4b") String model;
    @ConfigProperty(name="aies.execution.real-specialist.timeout-seconds", defaultValue="25") int timeoutSeconds;
    private MessageConsumer<JsonObject> consumer;
    @PostConstruct void start() {
        if (!enabled) return;
        var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper, baseUrl, model, timeoutSeconds);
        consumer = vertx.eventBus().localConsumer(ExecutionWorkerEngine.EXECUTE, msg ->
            vertx.<JsonObject>executeBlocking(() -> executor.execute(msg.body()), false)
                .onSuccess(msg::reply).onFailure(e -> msg.fail(503, e.getClass().getSimpleName())));
    }
    @PreDestroy void stop() { if (consumer != null) consumer.unregister(); }
}
