package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.*;
import dev.aies.control.planning.PlanningContracts;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** One bounded, operator-triggered attempt. No shell, network or project writes here. */
public final class ExecutionWorkerEngine {
    public static final String EXECUTE = "aies.execution.specialist.execute.v1";
    private final Vertx vertx;
    private final ObjectMapper mapper;
    private final Path root;
    private final int maxAttempts;
    private final long leaseSeconds;
    private final int timeoutSeconds;
    private dev.aies.control.specialists.GovernedSpecialistSelector dispatchGuard;
    public ExecutionWorkerEngine(Vertx vertx, ObjectMapper mapper, Path root, int maxAttempts,
                                 long leaseSeconds, int timeoutSeconds) {
        if (maxAttempts < 1 || maxAttempts > 20 || leaseSeconds < 10 || leaseSeconds > 3600 ||
            timeoutSeconds < 1 || timeoutSeconds >= leaseSeconds)
            throw new IllegalArgumentException("Timeout must be shorter than lease");
        this.vertx=vertx; this.mapper=mapper; this.root=root;
        this.maxAttempts=maxAttempts; this.leaseSeconds=leaseSeconds; this.timeoutSeconds=timeoutSeconds;
        this.dispatchGuard=null;
    }
    /** Explicit governed mode: never infer permission from model input or a selection response. */
    public ExecutionWorkerEngine(Vertx vertx, ObjectMapper mapper, Path root, int maxAttempts,
                                 long leaseSeconds, int timeoutSeconds,
                                 dev.aies.control.specialists.GovernedSpecialistSelector dispatchGuard) {
        this(vertx,mapper,root,maxAttempts,leaseSeconds,timeoutSeconds);
        this.dispatchGuard=java.util.Objects.requireNonNull(dispatchGuard);
    }
    public JsonObject runOne(String workflowId) throws Exception {
        ExecutionDispatchPolicy.id(workflowId);
        ExecutionHandoffStore.Work work;
        PlanningContracts.Task task;
        try (var store = handoff()) {
            var claimed=store.claim(workflowId,maxAttempts,leaseSeconds);
            if (claimed.isEmpty()) return new JsonObject().put("claimed",false);
            work=claimed.get();
            task=ExecutionWorkerPolicy.task(store.plan(workflowId),work.taskId(),work.specialistId());
        }
        try {
            var governedSnapshot = dispatchGuard == null ? null
                : dispatchGuard.snapshotForDispatch(work.specialistId(), task.capability());
            if (governedSnapshot == null) {
                try (var store = handoff()) {
                    store.requireNoGovernedHistory(workflowId, work.taskId());
                }
            }
            if (governedSnapshot != null) {
                try (var store = handoff()) {
                    store.bindGovernedAttempt(new ExecutionHandoffStore.GovernedAttempt(
                        workflowId, work.taskId(), work.leaseToken(), governedSnapshot.specialistId(),
                        governedSnapshot.version(), governedSnapshot.capability()));
                }
            }
            ExecutionCapabilityAuthorization authorization;
            try(var store=handoff()) { authorization=store.authorization(workflowId,work.taskId()).orElse(null); }
            if (authorization != null) {
                if (!"software-engineer".equals(work.specialistId()) || !"software".equals(task.capability()))
                    throw new SecurityException("Capability assignment mismatch");
                JsonObject docRequest = new JsonObject().put("workflowId",workflowId)
                    .put("taskId",work.taskId()).put("attemptId",work.leaseToken())
                    .put("approved",true).put("sourcePath",authorization.sourcePath())
                    .put("approvedSourceSha256",authorization.sourceSha256());
                if (governedSnapshot != null) {
                    docRequest.put("governedSpecialistId", governedSnapshot.specialistId())
                        .put("governedSpecialistVersion", governedSnapshot.version())
                        .put("governedCapability", governedSnapshot.capability());
                }
                JsonObject generated=vertx.eventBus().<JsonObject>request(WorkspaceDocumentationEventBus.GENERATE,
                    docRequest,new DeliveryOptions().setSendTimeout(timeoutSeconds*1000L))
                    .toCompletionStage().toCompletableFuture().get(timeoutSeconds+2L,TimeUnit.SECONDS).body();
                if(generated==null || !workflowId.equals(generated.getString("workflowId"))
                    || !work.taskId().equals(generated.getString("taskId"))
                    || !work.leaseToken().equals(generated.getString("attemptId")))
                    throw new SecurityException("Documentation correlation mismatch");
                String recordId=generated.getString("artifactRecordId");
                dev.aies.control.communication.CommunicationContracts.id(recordId);
                try(var comm=new CommunicationStore(root.resolve("communication.sqlite"),mapper)) {
                    ExecutionEvidenceVerifier.verify(comm,workflowId,work.taskId(),"artifact://communication/"+recordId);
                }
                try(var store=handoff()) {
                    if(!store.stageEvidence(workflowId,work.taskId(),work.leaseToken(),recordId))
                        throw new IllegalStateException("Lease expired before documentation staging");
                }
                return new JsonObject().put("claimed",true).put("taskId",work.taskId())
                    .put("status","EVIDENCE_PENDING_VERIFICATION").put("artifactRecordId",recordId);
            }
            JsonObject request = new JsonObject()
                .put("schemaVersion","1.0")
                .put("workflowId",workflowId).put("taskId",work.taskId())
                .put("specialistId",work.specialistId()).put("attemptId",work.leaseToken())
                .put("objective",task.objective()).put("capability",task.capability())
                .put("given",task.given()).put("when",task.when()).put("then",task.then());
            if (governedSnapshot != null) request.put("governedSpecialistVersion", governedSnapshot.version());
            JsonObject response=vertx.eventBus().<JsonObject>request(EXECUTE,request,
                new DeliveryOptions().setSendTimeout(timeoutSeconds*1000L))
                .toCompletionStage().toCompletableFuture().get(timeoutSeconds+2L,TimeUnit.SECONDS).body();
            // Reject stale, cross-workflow and cross-task replies before writing artifacts.
            if (response == null || !work.leaseToken().equals(response.getString("attemptId"))
                    || !workflowId.equals(response.getString("workflowId"))
                    || !work.taskId().equals(response.getString("taskId")))
                throw new SecurityException("Specialist response correlation mismatch");
            String content=ExecutionWorkerPolicy.content(response);
            var artifact=CommunicationContracts.validate(new CommunicationContracts.Artifact(
                CommunicationContracts.VERSION,workflowId,work.taskId(),work.specialistId(),
                "execution-result","text/markdown",content,List.of()));
            String recordId;
            try(var comm=new CommunicationStore(root.resolve("communication.sqlite"),mapper)) {
                recordId=comm.appendIdempotent("execution-"+work.leaseToken()+"-artifact", "ARTIFACT",workflowId,work.taskId(),artifact).id();
            }
            try(var store=handoff()) {
                if(!store.stageEvidence(workflowId,work.taskId(),work.leaseToken(),recordId))
                    throw new IllegalStateException("Lease expired before evidence staging");
            }
            // P1.5 must verify evidence and audit before COMPLETED.
            return new JsonObject().put("claimed",true).put("taskId",work.taskId())
                .put("status","EVIDENCE_PENDING_VERIFICATION")
                .put("artifactRecordId",recordId);
        } catch(Exception error) {
            try (var store = handoff()) {
                boolean updated = store.fail(workflowId, work.taskId(), work.leaseToken(), true, maxAttempts);
                String durableStatus = store.list(workflowId).stream()
                    .filter(w -> w.taskId().equals(work.taskId()))
                    .findFirst().orElseThrow().status();
                return new JsonObject().put("claimed", true).put("taskId", work.taskId())
                    .put("status", updated
                        ? ("DEAD".equals(durableStatus) ? "DEAD" : "RETRY_PENDING")
                        : "RECONCILIATION_REQUIRED")
                    .put("failureType", error.getClass().getSimpleName());
            }
        }
    }
    private ExecutionHandoffStore handoff() throws Exception {
        return new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"),mapper);
    }
}
