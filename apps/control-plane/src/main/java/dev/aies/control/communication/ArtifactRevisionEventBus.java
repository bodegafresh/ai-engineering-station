package dev.aies.control.communication;

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
import java.sql.DriverManager;
import java.util.List;

/** P0.12 remediation: revisions are immutable, gated and transported only on Vert.x Event Bus. */
@Startup @ApplicationScoped
public class ArtifactRevisionEventBus {
    public static final String REVISE="aies.communication.artifact.revise.v1";
    @Inject Vertx vertx;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="aies.agent-communications.enabled",defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.agent-communications.data-root",defaultValue="./data/agent-communications") String dataRoot;
    private MessageConsumer<JsonObject> consumer;
    @PostConstruct void start() {
        if(!enabled) return;
        consumer=vertx.eventBus().localConsumer(REVISE,message ->
            vertx.<JsonObject>executeBlocking(() -> revise(message.body()),false)
                .onSuccess(message::reply).onFailure(error -> message.fail(422,"Revision rejected: "+error.getClass().getSimpleName())));
    }
    private JsonObject revise(JsonObject input) throws Exception {
        String parentId=input.getString("parentRecordId");
        String content=input.getString("content");
        CommunicationContracts.id(parentId);
        if(content==null || content.isBlank() || content.length()>262144) throw new IllegalArgumentException("Invalid content");
        Path database=Path.of(dataRoot).resolve("communication.sqlite");
        try(var store=new CommunicationStore(database,mapper)) {
            var parent=store.find(parentId).orElseThrow(() -> new IllegalArgumentException("Parent not found"));
            if(!"ARTIFACT".equals(parent.kind())) throw new IllegalArgumentException("Only artifacts can be revised");
            var audits=store.list(parent.workflowId()).stream().filter(r -> "AUDIT".equals(r.kind()))
                .filter(r -> ((CommunicationContracts.Audit)r.payload()).subjectId().equals(parentId)).toList();
            if(audits.isEmpty() || ((CommunicationContracts.Audit)audits.getLast().payload()).decision()!=CommunicationContracts.AuditDecision.REVISION_REQUIRED)
                throw new IllegalArgumentException("Parent does not require revision");
            var original=(CommunicationContracts.Artifact)parent.payload();
            var revised=CommunicationContracts.validate(new CommunicationContracts.Artifact(original.schemaVersion(),
                original.workflowId(),original.taskId(),original.producer(),original.kind(),original.contentType(),content,original.sourceRefs()));
            // Validate the whole revision chain and prevent unbounded iterations.
            int max=((CommunicationContracts.Audit)audits.getLast().payload()).maxIterations();
            long audited=store.list(parent.workflowId()).stream().filter(r -> "AUDIT".equals(r.kind()) && parent.taskId().equals(r.taskId())).count();
            if(audited>=max) throw new IllegalArgumentException("Audit iteration budget exhausted");
            var saved=store.append("ARTIFACT",parent.workflowId(),parent.taskId(),revised);
            // Append-only lineage table: the parent and child remain independently auditable.
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+database.toAbsolutePath().normalize());
                var schema=db.createStatement()) {
                schema.execute("PRAGMA busy_timeout=5000");
                schema.execute("CREATE TABLE IF NOT EXISTS communication_revision_links(child_id TEXT PRIMARY KEY,parent_id TEXT NOT NULL,created_at TEXT NOT NULL)");
                try(var ps=db.prepareStatement("INSERT INTO communication_revision_links VALUES(?,?,?)")) {
                    ps.setString(1,saved.id());ps.setString(2,parentId);ps.setString(3,java.time.Instant.now().toString());ps.executeUpdate();
                }
            }
            return new JsonObject().put("parentRecordId",parentId).put("recordId",saved.id())
                .put("status","AUDIT_REQUIRED").put("iteration",audited+1);
        }
    }
    @PreDestroy void stop(){if(consumer!=null)consumer.unregister();}
}
