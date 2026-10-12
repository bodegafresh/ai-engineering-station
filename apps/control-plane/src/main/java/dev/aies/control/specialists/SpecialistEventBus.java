package dev.aies.control.specialists;

import io.quarkus.runtime.Startup;
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
    private MessageConsumer<JsonObject> consumer;
    private final List<SpecialistCatalog.Specialist> catalog = List.of(
        new SpecialistCatalog.Specialist("researcher",Set.of("research"),Set.of(),2,1),
        new SpecialistCatalog.Specialist("requirements-auditor",Set.of("requirements","audit"),Set.of(),1,2),
        new SpecialistCatalog.Specialist("software-engineer",Set.of("software","implementation"),Set.of(),1,3),
        new SpecialistCatalog.Specialist("experiment-analyst",Set.of("statistics","experiments"),Set.of(),1,2)
    );
    @PostConstruct void start() {
        if (!enabled) return;
        consumer = vertx.eventBus().localConsumer(SELECT, message -> {
            try {
                JsonObject req = message.body();
                Set<String> capabilities = strings(req.getJsonArray("capabilities"));
                Set<String> permittedTools = strings(req.getJsonArray("permittedTools",new JsonArray()));
                int budget = req.getInteger("budget",0);
                var result = SpecialistCatalog.select(catalog,capabilities,permittedTools,budget);
                if (result.isEmpty()) message.reply(new JsonObject().put("selected",false));
                else message.reply(new JsonObject().put("selected",true).put("specialistId",result.get().specialistId())
                    .put("costUnits",result.get().costUnits()));
            } catch(Exception e) {message.fail(400,"Invalid specialist selection request");}
        });
    }
    private static Set<String> strings(JsonArray input) {
        if (input == null || input.size() > 32) throw new IllegalArgumentException("Invalid list");
        Set<String> result = new HashSet<>();
        for (Object o: input) {
            if (!(o instanceof String s)) throw new IllegalArgumentException("Invalid capability");
            dev.aies.control.communication.CommunicationContracts.id(s);
            result.add(s);
        }
        return Set.copyOf(result);
    }
    @PreDestroy void stop() {if (consumer != null) consumer.unregister();}
}
