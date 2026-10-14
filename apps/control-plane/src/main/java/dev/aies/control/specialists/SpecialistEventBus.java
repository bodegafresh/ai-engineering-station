package dev.aies.control.specialists;

import io.quarkus.runtime.Startup;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.*;

/** Internal specialist selection always travels through Vert.x Event Bus. */
@Startup
@ApplicationScoped
public class SpecialistEventBus {
    public static final String SELECT = "aies.specialists.select.v1";
    @Inject Vertx vertx;
    @ConfigProperty(name="aies.specialists.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.specialists.governed-selection.enabled", defaultValue="false") String governedFlag;
    @ConfigProperty(name="aies.specialists.governed-selection.database", defaultValue="./data/specialists.sqlite") String governedDatabase;
    private MessageConsumer<JsonObject> consumer;
    private final List<SpecialistCatalog.Specialist> catalog = List.of(
        new SpecialistCatalog.Specialist("researcher",Set.of("research"),Set.of(),2,1),
        new SpecialistCatalog.Specialist("requirements-auditor",Set.of("requirements","audit"),Set.of(),1,2),
        new SpecialistCatalog.Specialist("software-engineer",Set.of("software","implementation"),Set.of(),1,3),
        new SpecialistCatalog.Specialist("experiment-analyst",Set.of("statistics","experiments"),Set.of(),1,2)
    );
    @PostConstruct void start() {
        if (!enabled) return;
        // Construct at startup, never from request-supplied flags or paths.
        SpecialistSelectionBridge bridge = new SpecialistSelectionBridge(
            governedFlag, Path.of(governedDatabase), new ObjectMapper());
        consumer = vertx.eventBus().localConsumer(SELECT, message -> {
            // SQLite is blocking I/O: never execute governed selection on the Vert.x event loop.
            vertx.<JsonObject>executeBlocking(() -> bridge.select(message.body()), false)
                .onSuccess(message::reply)
                .onFailure(error -> {
                    if (error instanceof IllegalArgumentException || error instanceof NullPointerException)
                        message.fail(400, "Invalid specialist selection request");
                    else message.fail(503, "Specialist selection unavailable");
                });
        });
    }
    @PreDestroy void stop() {if (consumer != null) consumer.unregister();}
}
