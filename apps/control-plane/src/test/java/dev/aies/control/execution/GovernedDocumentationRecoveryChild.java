package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.nio.file.Path;

/** Test-only process: reopens all stores and recovers an already reserved attempt. */
public final class GovernedDocumentationRecoveryChild {
    private GovernedDocumentationRecoveryChild() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("root port token sha");
        Path root = Path.of(args[0]);
        ObjectMapper mapper = new ObjectMapper();
        var selector = new GovernedSpecialistSelector(root.resolve("registry.sqlite"), mapper, true);
        var snapshot = selector.snapshotForDispatch("software-engineer", "software");
        var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper,
            "http://127.0.0.1:" + Integer.parseInt(args[1]), "qwen3:4b", 5);
        var service = new WorkspaceDocumentationService(root, root.resolve("journal"),
            executor, mapper, selector, snapshot);
        JsonObject result = service.generate(new JsonObject().put("workflowId", "wf-execution")
            .put("taskId", "a").put("attemptId", args[2]).put("sourcePath", "A.java")
            .put("approvedSourceSha256", args[3]).put("approved", true));
        if (!"EVIDENCE_PENDING".equals(result.getString("status"))
            || result.getString("artifactRecordId") == null)
            throw new IllegalStateException("Recovery produced no durable evidence");
        System.out.println("RECOVERED " + result.getString("artifactRecordId"));
    }
}
