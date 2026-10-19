package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.nio.file.Path;

/** Real governed publisher, paused while it owns the production publication gate. */
public final class GovernedDocumentationPublicationBarrierChild {
    private GovernedDocumentationPublicationBarrierChild() {}
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
        service.publicationCheckpoint(phase -> {
            if ("AFTER_FILE_JOURNAL".equals(phase)) {
                System.out.println("PUBLISHER_HOLDS_GATE");
                System.out.flush();
                int command = System.in.read();
                if (command != 'R') throw new IllegalStateException("Missing release command");
            }
        });
        var result = service.generate(new JsonObject().put("workflowId", "wf-execution")
            .put("taskId", "a").put("attemptId", args[2]).put("sourcePath", "A.java")
            .put("approvedSourceSha256", args[3]).put("approved", true));
        if (!"EVIDENCE_PENDING".equals(result.getString("status")))
            throw new IllegalStateException("Unexpected publication result");
        System.out.println("PUBLISHED");
    }
}
