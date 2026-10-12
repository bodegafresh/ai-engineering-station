package dev.aies.control.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** In-process transport. Only record IDs cross the bus; SQLite holds canonical payloads. */
@Startup
@ApplicationScoped
public class CommunicationEventBus {
    public static final String AUDIT_REQUEST = "aies.communication.audit.request.v1";
    public static final String AUDIT_RESULT = "aies.communication.audit.result.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.agent-communications.data-root", defaultValue="./data/agent-communications") String dataRoot;
    @ConfigProperty(name="aies.agent-communications.audit-max-iterations", defaultValue="3") int maxIterations;
    @ConfigProperty(name="aies.semantic-audit.enabled", defaultValue="false") boolean semanticEnabled;
    @ConfigProperty(name="aies.model.base-url", defaultValue="http://127.0.0.1:11434") String modelBaseUrl;
    @ConfigProperty(name="aies.model.name", defaultValue="qwen3:4b") String modelName;
    @ConfigProperty(name="aies.semantic-audit.timeout-seconds", defaultValue="45") int semanticTimeout;
    private MessageConsumer<JsonObject> consumer;

    @jakarta.annotation.PostConstruct
    void start() {
        consumer = vertx.eventBus().localConsumer(AUDIT_REQUEST, request -> {
            JsonObject envelope = request.body();
            vertx.<JsonObject>executeBlocking(() -> audit(envelope), true)
                .onSuccess(result -> {
                    request.reply(result);
                    vertx.eventBus().publish(AUDIT_RESULT, result);
                })
                .onFailure(error -> request.fail(500, error.getClass().getSimpleName()));
        });
    }

    private JsonObject audit(JsonObject envelope) throws Exception {
        String id = Objects.requireNonNull(envelope.getString("recordId"));
        CommunicationContracts.id(id);
        if(maxIterations < 1 || maxIterations > 10) throw new IllegalStateException("Invalid audit iteration limit");
        try(var store = new CommunicationStore(Path.of(dataRoot).resolve("communication.sqlite"), mapper)) {
            var subject = store.find(id).orElseThrow();
            if ("AUDIT".equals(subject.kind())) throw new IllegalArgumentException("Cannot audit an audit");
            var prior = store.list(subject.workflowId()).stream()
                .filter(r -> "AUDIT".equals(r.kind()))
                .map(r -> (CommunicationContracts.Audit) r.payload())
                .filter(a -> a.subjectId().equals(id)).toList();
            if(!prior.isEmpty()) {
                var latest = prior.getLast();
                return new JsonObject().put("subjectId",id).put("auditRecordId",findLastAuditId(store.list(subject.workflowId()),id))
                    .put("decision",latest.decision().name()).put("iteration",latest.iteration())
                    .put("replayed",true);
            }
            // Each revision is a new immutable record, linked by the caller's workflow/task context.
            // The revision iteration count is derived from all audits for this task.
            long taskAudits = store.list(subject.workflowId()).stream()
                .filter(r -> "AUDIT".equals(r.kind()) && r.taskId().equals(subject.taskId())).count();
            int iteration = (int)Math.min((long)maxIterations,taskAudits+1);
            List<String> findings = new java.util.ArrayList<>(CommunicationQualityPolicy.findings(subject));
            if (findings.isEmpty() && semanticEnabled) {
                var semantic = SemanticAuditPolicy.review(subject,mapper,modelBaseUrl,modelName,semanticTimeout);
                if (!semantic.approved()) findings.addAll(semantic.findings());
            }
            var decision = findings.isEmpty() ? CommunicationContracts.AuditDecision.APPROVED :
                iteration >= maxIterations ? CommunicationContracts.AuditDecision.BLOCKED :
                CommunicationContracts.AuditDecision.REVISION_REQUIRED;
            var audit = CommunicationContracts.validate(new CommunicationContracts.Audit(
                CommunicationContracts.VERSION,subject.workflowId(),id,"structural-quality-auditor",
                decision,iteration,maxIterations,findings,
                findings.isEmpty()?List.of():List.of("Revise the identified issues and submit a new immutable record")));
            var saved = store.append("AUDIT",subject.workflowId(),subject.taskId(),audit);
            return new JsonObject().put("subjectId",id).put("auditRecordId",saved.id())
                .put("decision",decision.name()).put("iteration",iteration).put("replayed",false);
        }
    }
    private static String findLastAuditId(List<CommunicationStore.StoredRecord> records,String subjectId) {
        String id = null;
        for(var record:records) if("AUDIT".equals(record.kind()) &&
            ((CommunicationContracts.Audit)record.payload()).subjectId().equals(subjectId)) id=record.id();
        return id;
    }
    @PreDestroy void stop() { if(consumer!=null) consumer.unregister(); }
}
