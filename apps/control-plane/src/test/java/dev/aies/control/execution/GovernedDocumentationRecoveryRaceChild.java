package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Independent JVM recovery contender with a file-based test start gate. */
public final class GovernedDocumentationRecoveryRaceChild {
    private GovernedDocumentationRecoveryRaceChild() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=5) throw new IllegalArgumentException("root port token sha gate");
        Path gate=Path.of(args[4]);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(!Files.exists(gate)) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("Gate timeout");
            Thread.sleep(5);
        }
        Path root=Path.of(args[0]);
        var mapper=new ObjectMapper();
        var selector=new GovernedSpecialistSelector(root.resolve("registry.sqlite"),mapper,true);
        var snapshot=selector.snapshotForDispatch("software-engineer","software");
        var executor=new OllamaSpecialistExecutor(HttpClient.newHttpClient(),mapper,
            "http://127.0.0.1:"+Integer.parseInt(args[1]),"qwen3:4b",5);
        var service=new WorkspaceDocumentationService(root,root.resolve("journal"),
            executor,mapper,selector,snapshot);
        JsonObject result=service.generate(new JsonObject().put("workflowId","wf-execution")
            .put("taskId","a").put("attemptId",args[2]).put("sourcePath","A.java")
            .put("approvedSourceSha256",args[3]).put("approved",true));
        if(!"EVIDENCE_PENDING".equals(result.getString("status")) ||
            result.getString("artifactRecordId")==null)
            throw new IllegalStateException("No artifact");
        System.out.println("RECOVERED "+result.getString("artifactRecordId"));
    }
}
