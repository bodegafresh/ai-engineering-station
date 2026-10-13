package dev.aies.control.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import dev.aies.control.specialists.SpecialistEventBus;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.*;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** P0.14-17: durable planning orchestration. All inter-agent calls use Event Bus. */
@Startup @ApplicationScoped
public class PlanningEventBus {
 public static final String PROCESS="aies.planning.process.v1";
 public static final String AUDIT="aies.planning.audit.v1";
 @Inject Vertx vertx;
 @Inject ObjectMapper mapper;
 @ConfigProperty(name="aies.planning.enabled",defaultValue="false") boolean enabled;
 @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String root;
 @ConfigProperty(name="aies.planning.poll-ms",defaultValue="2000") long pollMs;
 private MessageConsumer<JsonObject> processConsumer,auditConsumer;
 private long timer=-1;
 private final AtomicBoolean polling=new AtomicBoolean();
 private Path database(){return Path.of(root).resolve("planning.sqlite");}
 @PostConstruct void start(){
  if(!enabled)return;
  processConsumer=vertx.eventBus().localConsumer(PROCESS,msg->
   vertx.<JsonObject>executeBlocking(()->process(msg.body()),false)
    .onSuccess(result->{
     msg.reply(result);
     if("AUDIT_REQUIRED".equals(result.getString("status")))
       vertx.eventBus().<JsonObject>request(AUDIT,new JsonObject().put("workflowId",result.getString("workflowId")),
         new DeliveryOptions().setSendTimeout(125000))
         .onFailure(error->{}); // persisted AWAITING_AUDIT allows explicit operator retry
    }).onFailure(e->msg.fail(422,e.getClass().getSimpleName())));
  auditConsumer=vertx.eventBus().localConsumer(AUDIT,msg->
   vertx.<JsonObject>executeBlocking(()->prepareAudit(msg.body()),false)
    .onSuccess(msg::reply).onFailure(e->msg.fail(422,e.getClass().getSimpleName())));
  if(pollMs<250||pollMs>60000)throw new IllegalStateException("Invalid planning poll");
  timer=vertx.setPeriodic(pollMs,unused->dispatch());dispatch();
 }
 private JsonObject process(JsonObject body)throws Exception {
  String eventId=body.getString("eventId");
  String id=body.getString("workflowId");
  PlanningContracts.id(id);PlanningContracts.id(eventId);
  try(var store=new PlanningStore(database(),mapper)){
   var snapshot=store.find(id).orElseThrow();
   if(!"RECEIVED".equals(snapshot.status()))
    return new JsonObject().put("workflowId",id).put("status",snapshot.status()).put("replayed",true);
   var intake=snapshot.intake();
   var review=PlanningPolicy.review(intake);
   var assignments=new LinkedHashMap<String,PlanningContracts.Task>();
   if(review.approved()){
    for(var r:intake.requirements()){
     var reply=vertx.eventBus().<JsonObject>request(SpecialistEventBus.SELECT,
       new JsonObject().put("capabilities",new JsonArray().add(r.capability()))
        .put("permittedTools",new JsonArray()).put("budget",intake.budgetUnits()),
       new DeliveryOptions().setSendTimeout(5000)).toCompletionStage().toCompletableFuture().get().body();
     if(!reply.getBoolean("selected",false)){
      assignments.put(r.id(),new PlanningContracts.Task("task-"+r.id(),r.id(),r.capability(),
        r.dependsOn().stream().map(x->"task-"+x).toList(),"unassigned",0,r.statement(),
        r.given(),r.when(),r.then(),r.verification()));
      continue;
     }
     assignments.put(r.id(),new PlanningContracts.Task("task-"+r.id(),r.id(),r.capability(),
       r.dependsOn().stream().map(x->"task-"+x).toList(),reply.getString("specialistId"),
       reply.getInteger("costUnits"),r.statement(),r.given(),r.when(),r.then(),r.verification()));
    }
   } else {
    // Keep unresolved questions visible without fabricating specialist assignments.
    for(var r:intake.requirements())assignments.put(r.id(),new PlanningContracts.Task("task-"+r.id(),
      r.id(),r.capability(),r.dependsOn().stream().map(x->"task-"+x).toList(),
      "unassigned",0,r.statement(),r.given(),r.when(),r.then(),r.verification()));
   }
   var plan=PlanningPolicy.build(intake,assignments);
   store.complete(eventId,plan);
   return new JsonObject().put("workflowId",id).put("status",plan.status());
  }
 }
 private JsonObject prepareAudit(JsonObject body)throws Exception {
  String id=body.getString("workflowId");PlanningContracts.id(id);
  try(var store=new PlanningStore(database(),mapper);
      var comm=new CommunicationStore(Path.of(root).resolve("communication.sqlite"),mapper)){
   var snapshot=store.find(id).orElseThrow();
   if(!Set.of("AUDIT_REQUIRED","AWAITING_AUDIT").contains(snapshot.status()))
    return new JsonObject().put("workflowId",id).put("status",snapshot.status())
     .put("recordId",snapshot.artifactRecordId());
   String recordId=snapshot.artifactRecordId();
   if("AUDIT_REQUIRED".equals(snapshot.status())){
    String content="# Planning contract: "+snapshot.plan().objective()+"\\n\\n"+
      "## Requirements and task assignments\\n\\n"+
      mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot.plan())+
      "\\n\\n## State\\nPRE-EXECUTION PLAN. No implementation or empirical results claimed.\\n";
    var artifact=PlanningArtifactEvidence.artifact(snapshot.intake(),snapshot.plan(),content);
    var saved=comm.append("ARTIFACT",id,"planning-contract",artifact);
    if(!store.attachArtifact(id,saved.id()))throw new IllegalStateException("Concurrent plan update");
    recordId=saved.id();
   }
   var audit=vertx.eventBus().<JsonObject>request(CommunicationEventBus.AUDIT_REQUEST,
       new JsonObject().put("recordId",recordId),
       new DeliveryOptions().setSendTimeout(120000))
       .toCompletionStage().toCompletableFuture().get().body();
   String decision=audit.getString("decision");
   String finalStatus=switch(decision){
     case "APPROVED" -> "READY_FOR_ASSIGNMENT";
     case "REVISION_REQUIRED" -> "REVISION_REQUIRED";
     default -> "BLOCKED";
   };
   if(!store.attachAudit(id,audit.getString("auditRecordId"),finalStatus))
     throw new IllegalStateException("Concurrent audit update");
   return new JsonObject().put("workflowId",id).put("recordId",recordId)
      .put("auditRecordId",audit.getString("auditRecordId")).put("decision",decision)
      .put("status",finalStatus);
  }
 }
 private void dispatch(){
  if(!polling.compareAndSet(false,true))return;
  vertx.<List<PlanningStore.Pending>>executeBlocking(()->{
   try(var s=new PlanningStore(database(),mapper)){return s.pending(20);}
  },false).onSuccess(events->{
   if(events.isEmpty()){polling.set(false);return;}
   var futures=events.stream().map(e->
    vertx.eventBus().<JsonObject>request(PROCESS,new JsonObject().put("eventId",e.id())
     .put("workflowId",e.workflowId()),new DeliveryOptions().setSendTimeout(30000))
     .<Void>mapEmpty().recover(error->vertx.<Void>executeBlocking(()->{
      try(var s=new PlanningStore(database(),mapper)){s.failed(e.id());}return null;
     },false))).toList();
   io.vertx.core.Future.all(futures).onComplete(done->polling.set(false));
  }).onFailure(e->polling.set(false));
 }
 @PreDestroy void stop(){
  if(timer>=0)vertx.cancelTimer(timer);
  if(processConsumer!=null)processConsumer.unregister();
  if(auditConsumer!=null)auditConsumer.unregister();
 }
}
