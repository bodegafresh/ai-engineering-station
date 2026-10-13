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
import java.nio.file.Path;

/** Explicit operator-approved documentation tool, separate from autonomous worker dispatch. */
@Startup @ApplicationScoped
public class WorkspaceDocumentationEventBus {
    public static final String GENERATE = "aies.execution.documentation.generate.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.execution.documentation.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.execution.documentation.workspace-root", defaultValue="./projects") String workspaceRoot;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String dataRoot;
    @ConfigProperty(name="aies.model.base-url", defaultValue="http://127.0.0.1:11434") String baseUrl;
    @ConfigProperty(name="aies.model.name", defaultValue="qwen3:4b") String model;
    @ConfigProperty(name="aies.execution.documentation.timeout-seconds", defaultValue="90") int timeoutSeconds;
    private MessageConsumer<JsonObject> consumer;
    @PostConstruct void start() {
        if (!enabled) return;
        consumer = vertx.eventBus().localConsumer(GENERATE, msg ->
            vertx.<JsonObject>executeBlocking(() -> {
                var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper, baseUrl, model, timeoutSeconds);
                var service = new WorkspaceDocumentationService(Path.of(workspaceRoot), Path.of(dataRoot), executor, mapper);
                return service.generate(msg.body());
            }, false).onSuccess(msg::reply).onFailure(e -> msg.fail(422, e.getClass().getSimpleName())));
    }
    @PreDestroy void stop() { if (consumer != null) consumer.unregister(); }
}
