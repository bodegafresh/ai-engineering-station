package dev.aies.control.science;

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
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** P0.6: all internal workflow commands use Vert.x local Event Bus; DB is durable authority. */
@Startup
@ApplicationScoped
public class ScientificWorkflowEventBus {
    public static final String PLAN_CREATED="aies.science.plan-created.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.science.outbox.poll-ms",defaultValue="2000") long pollMs;
    private MessageConsumer<JsonObject> consumer;
    private long timerId=-1;
    private final AtomicBoolean polling=new AtomicBoolean();
    private Path database() {return Path.of(dataRoot).resolve("science.sqlite");}
    @PostConstruct void start() {
        if(!enabled) return;
        consumer=vertx.eventBus().localConsumer(PLAN_CREATED,message ->
            vertx.<JsonObject>executeBlocking(() -> {
                String eventId=message.body().getString("eventId");
                try(var store=new ScientificWorkflowStore(database(),mapper)) {
                    boolean processed=store.acknowledge(eventId,"science-plan-processor");
                    return new JsonObject().put("eventId",eventId).put("processed",processed);
                }
            },false).onSuccess(message::reply).onFailure(e->message.fail(500,"Processing failed")));
        if(pollMs < 250 || pollMs > 60000) throw new IllegalStateException("Invalid outbox poll interval");
        timerId=vertx.setPeriodic(pollMs, ignored -> dispatch());
        dispatch();
    }
    private void dispatch() {
        if(!polling.compareAndSet(false,true)) return;
        vertx.<java.util.List<ScientificWorkflowStore.Event>>executeBlocking(() -> {
            try(var store=new ScientificWorkflowStore(database(),mapper)) {return store.pending(20);}
        },false).onSuccess(events -> {
            if(events.isEmpty()) {polling.set(false);return;}
            var futures=events.stream().map(event -> {
                if(!"PLAN_CREATED".equals(event.type())) {
                    return vertx.<Void>executeBlocking(() -> {try(var store=new ScientificWorkflowStore(database(),mapper)) {store.failed(event.id(),5);}return null;},false);
                }
                return vertx.eventBus().<JsonObject>request(PLAN_CREATED,new JsonObject().put("eventId",event.id()),
                    new io.vertx.core.eventbus.DeliveryOptions().setSendTimeout(10000))
                    .<Void>mapEmpty().recover(err -> vertx.<Void>executeBlocking(() -> {
                        try(var store=new ScientificWorkflowStore(database(),mapper)) {store.failed(event.id(),5);}return null;
                    },false));
            }).toList();
            io.vertx.core.Future.all(futures).onComplete(done -> polling.set(false));
        }).onFailure(err -> polling.set(false));
    }
    @PreDestroy void stop() {
        if(timerId>=0) vertx.cancelTimer(timerId);
        if(consumer!=null) consumer.unregister();
    }
}
