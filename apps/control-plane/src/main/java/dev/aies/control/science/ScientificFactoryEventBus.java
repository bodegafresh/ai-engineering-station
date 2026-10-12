package dev.aies.control.science;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import dev.aies.control.communication.CommunicationEventBus;
import dev.aies.control.communication.CommunicationStore;
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
import java.util.List;

/** P0.12/13: internal commands use Vert.x Event Bus; REST is operator ingress only. */
@Startup @ApplicationScoped
public class ScientificFactoryEventBus {
    public static final String PREPARE="aies.science.factory.prepare.v1";
    public static final String DELIVERY="aies.science.factory.delivery.v1";
    public static final String BUSINESS="aies.science.product.business.v1";
    public static final String TRADING="aies.science.product.trading.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.science.enabled",defaultValue="false") boolean scienceEnabled;
    @ConfigProperty(name="aies.factory.enabled",defaultValue="false") boolean factoryEnabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private MessageConsumer<JsonObject> prepare,delivery,business,trading;
    private Path root(){return Path.of(dataRoot);}
    @PostConstruct void start(){
        if(!scienceEnabled || !factoryEnabled) return;
        prepare=vertx.eventBus().localConsumer(PREPARE,msg -> vertx.<JsonObject>executeBlocking(() -> {
            String id=msg.body().getString("workflowId");
            try(var science=new ScientificWorkflowStore(root().resolve("science.sqlite"),mapper);
                var communication=new CommunicationStore(root().resolve("communication.sqlite"),mapper)) {
                var snapshot=science.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown workflow"));
                if(!"READY_FOR_EXPERIMENT".equals(snapshot.status())) throw new IllegalStateException("Workflow not ready");
                var plan=snapshot.plan();
                StringBuilder markdown=new StringBuilder("# ").append(plan.objective()).append("\n\n## Domain\n").append(plan.domain()).append("\n\n## Hypotheses\n");
                for(var h:plan.hypotheses()) markdown.append("### ").append(h.id()).append("\nClaim: ").append(h.claim()).append("\nNull: ").append(h.nullHypothesis()).append("\nFalsification: ").append(h.falsificationRule()).append("\nBaseline: ").append(h.baseline()).append("\nMinimum sample: ").append(h.minimumSample()).append("\n");
                markdown.append("\n## Experiments\n");
                for(var e:plan.experiments()) markdown.append("### ").append(e.id()).append("\nMethod: ").append(e.method()).append("\nMetric: ").append(e.primaryMetric()).append("\nDecision: ").append(e.decisionRule()).append("\nBudget: ").append(e.budgetLimit()).append("\n");
                markdown.append("\n## Evidence\n");
                for(String ref:plan.evidenceRefs()) markdown.append("- ").append(ref).append("\n");
                markdown.append("\nStatus: PLAN ONLY. No implementation, experiments, or deployment have been executed.\n");
                var artifact=CommunicationContracts.validate(new CommunicationContracts.Artifact(CommunicationContracts.VERSION,id,"scientific-plan","scientific-factory","scientific-plan","text/markdown",markdown.toString(),List.of()));
                var saved=communication.append("ARTIFACT",id,"scientific-plan",artifact);
                return new JsonObject().put("workflowId",id).put("recordId",saved.id()).put("status","AUDIT_REQUIRED");
            }
        },false).onSuccess(msg::reply).onFailure(e->msg.fail(422,"Plan preparation rejected")));
        delivery=vertx.eventBus().localConsumer(DELIVERY,msg -> vertx.<JsonObject>executeBlocking(() -> {
            String id=msg.body().getString("recordId");
            try(var store=new CommunicationStore(root().resolve("communication.sqlite"),mapper)) {
                var record=store.find(id).orElseThrow();
                boolean approved=ScientificDeliveryPolicy.approved(id,store.list(record.workflowId()));
                return new JsonObject().put("recordId",id).put("deliverable",approved).put("decision",approved?"APPROVED":"BLOCKED_OR_PENDING");
            }
        },false).onSuccess(msg::reply).onFailure(e->msg.fail(422,"Delivery check rejected")));
        business=vertx.eventBus().localConsumer(BUSINESS,msg -> vertx.<JsonObject>executeBlocking(() -> JsonObject.mapFrom(
            ScientificProductMetrics.business(mapper.readValue(msg.body().encode(),ScientificProductMetrics.Business.class))),false)
            .onSuccess(msg::reply).onFailure(e->msg.fail(422,"Invalid business input")));
        trading=vertx.eventBus().localConsumer(TRADING,msg -> vertx.<JsonObject>executeBlocking(() -> JsonObject.mapFrom(
            ScientificProductMetrics.trading(mapper.readValue(msg.body().encode(),ScientificProductMetrics.Trading.class))),false)
            .onSuccess(msg::reply).onFailure(e->msg.fail(422,"Invalid trading input")));
    }
    @PreDestroy void stop(){if(prepare!=null)prepare.unregister();if(delivery!=null)delivery.unregister();if(business!=null)business.unregister();if(trading!=null)trading.unregister();}
}
