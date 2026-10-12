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

/** P0.10/P0.11 internal commands: only Vert.x Event Bus, never internal HTTP. */
@Startup @ApplicationScoped
public class ScientificResearchEventBus {
    public static final String SOURCE="aies.science.source.record.v1";
    public static final String OBSERVATION="aies.science.observation.record.v1";
    public static final String RETROSPECTIVE="aies.science.retrospective.request.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private MessageConsumer<JsonObject> sourceConsumer,observationConsumer,reportConsumer;
    private java.nio.file.Path database(){return java.nio.file.Path.of(dataRoot).resolve("science.sqlite");}
    @PostConstruct void start(){
        if(!enabled)return;
        sourceConsumer=vertx.eventBus().localConsumer(SOURCE,message->vertx.<JsonObject>executeBlocking(()->{
            var source=mapper.readValue(message.body().getJsonObject("source").encode(),ResearchEvidence.Source.class);
            try(var store=new ResearchEvidenceStore(database(),mapper)){return JsonObject.mapFrom(store.addSource(source));}
        },false).onSuccess(message::reply).onFailure(e->message.fail(422,"Source rejected")));
        observationConsumer=vertx.eventBus().localConsumer(OBSERVATION,message->vertx.<JsonObject>executeBlocking(()->{
            var input=message.body();
            try(var store=new ResearchEvidenceStore(database(),mapper)){
                return JsonObject.mapFrom(store.addObservation(input.getString("workflowId"),input.getString("experimentId"),
                    Boolean.TRUE.equals(input.getBoolean("success")),input.getDouble("cost"),input.getDouble("durationSeconds"),input.getString("notes")));
            }
        },false).onSuccess(message::reply).onFailure(e->message.fail(422,"Observation rejected")));
        reportConsumer=vertx.eventBus().localConsumer(RETROSPECTIVE,message->vertx.<JsonObject>executeBlocking(()->{
            String workflowId=message.body().getString("workflowId");
            try(var science=new ScientificWorkflowStore(database(),mapper);var store=new ResearchEvidenceStore(database(),mapper)){
                if(science.find(workflowId).isEmpty())throw new IllegalArgumentException("Unknown workflow");
                return JsonObject.mapFrom(ScientificRetrospective.calculate(workflowId,store.sources(workflowId),store.observations(workflowId)));
            }
        },false).onSuccess(message::reply).onFailure(e->message.fail(422,"Retrospective unavailable")));
    }
    @PreDestroy void stop(){if(sourceConsumer!=null)sourceConsumer.unregister();if(observationConsumer!=null)observationConsumer.unregister();if(reportConsumer!=null)reportConsumer.unregister();}
}
