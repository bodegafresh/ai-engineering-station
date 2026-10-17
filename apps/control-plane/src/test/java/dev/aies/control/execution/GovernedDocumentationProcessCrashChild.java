package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.specialists.GovernedSpecialistSelector;
import io.vertx.core.json.JsonObject;
import java.net.http.HttpClient;
import java.nio.file.Path;

/** Test-only subprocess. Halt is deliberately abrupt: no finally blocks or JVM shutdown hooks. */
public final class GovernedDocumentationProcessCrashChild {
    private GovernedDocumentationProcessCrashChild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("root, port, token, sha, checkpoint required");
        Path root = Path.of(args[0]);
        int port = Integer.parseInt(args[1]);
        String token = args[2];
        String sha = args[3];
        String checkpoint = args[4];
        if (!java.util.Set.of("AFTER_FILE_JOURNAL", "AFTER_ATOMIC_MOVE", "AFTER_ARTIFACT_APPEND").contains(checkpoint))
            throw new IllegalArgumentException("Unexpected checkpoint");
        ObjectMapper mapper = new ObjectMapper();
        var selector = new GovernedSpecialistSelector(root.resolve("registry.sqlite"), mapper, true);
        var snapshot = selector.snapshotForDispatch("software-engineer", "software");
        var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(), mapper,
            "http://127.0.0.1:" + port, "qwen3:4b", 5);
        var service = new WorkspaceDocumentationService(root, root.resolve("journal"), executor,
            mapper, selector, snapshot);
        service.publicationCheckpoint(phase -> {
            if (checkpoint.equals(phase)) Runtime.getRuntime().halt(73);
        });
        service.generate(new JsonObject().put("workflowId", "wf-execution")
            .put("taskId", "a").put("attemptId", token).put("sourcePath", "A.java")
            .put("approvedSourceSha256", sha).put("approved", true));
        System.exit(74); // Reaching this point means the expected checkpoint was missed.
    }
}
