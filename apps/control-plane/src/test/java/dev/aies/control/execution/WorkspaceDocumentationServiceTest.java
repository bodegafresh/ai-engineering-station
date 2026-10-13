package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDocumentationServiceTest {
    @TempDir Path root;
    private JsonObject request(String path) {
        return new JsonObject().put("workflowId","wf-1").put("taskId","task-1")
            .put("attemptId","attempt-1").put("approved",true).put("sourcePath",path);
    }
    private WorkspaceDocumentationService service() throws Exception {
        return new WorkspaceDocumentationService(root, root.resolve("journal"),
            new OllamaSpecialistExecutor(HttpClient.newHttpClient(), new ObjectMapper(), "http://127.0.0.1:1", "qwen3:4b", 2), new ObjectMapper());
    }
    @Test void rejectsUnauthorizedAndEscapingPaths() throws Exception {
        Files.writeString(root.resolve("A.java"), "public class A {}\n");
        assertThrows(SecurityException.class, () -> service().generate(request("A.java").put("approved",false)));
        assertThrows(SecurityException.class, () -> service().generate(request("../A.java")));
        assertThrows(SecurityException.class, () -> service().generate(request("/etc/passwd")));
        Files.writeString(root.resolve("secret.txt"), "secret");
        assertThrows(SecurityException.class, () -> service().generate(request("secret.txt")));
    }
    @Test void rejectsSymlinkSource() throws Exception {
        Path external = Files.createTempFile("outside-aies-", ".java");
        try {
            Files.writeString(external, "public class Secret {}\n");
            Files.createSymbolicLink(root.resolve("Link.java"), external);
            assertThrows(SecurityException.class, () -> service().generate(request("Link.java")));
        } finally { Files.deleteIfExists(external); }
    }
    @Test void producesActualDocumentAndDurableArtifact() throws Exception {
        Files.writeString(root.resolve("A.java"), "public class A { public int value() { return 1; } }\n");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(payload.contains("public class A"));
            byte[] response = "{\"response\":\"# A\\nDocuments value().\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(),new ObjectMapper(),
                "http://127.0.0.1:"+server.getAddress().getPort(),"qwen3:4b",5);
            var service = new WorkspaceDocumentationService(root,root.resolve("journal"),executor,new ObjectMapper());
            // F3.4 requires a durable operator grant and a live task lease.
            // The legacy approved=true flag is deliberately insufficient.
            String sha = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(root.resolve("A.java"))));
            String attempt;
            try (var handoff = new ExecutionHandoffStore(
                    root.resolve("journal/execution-handoff.sqlite"), new ObjectMapper())) {
                handoff.importPlan(ExecutionGraphTest.plan(ExecutionGraphTest.task("task-1")));
                // The test's request uses wf-1; the imported fixture uses wf-execution.
                // Use the imported workflow identity consistently for grant and lease.
                handoff.authorizeCapability(new ExecutionCapabilityAuthorization(
                    "wf-execution", "task-1", "workspace-documentation", "A.java", sha));
                attempt = handoff.claim("wf-execution", 3, 60).orElseThrow().leaseToken();
            }
            var authorizedRequest = request("A.java")
                .put("workflowId", "wf-execution")
                .put("attemptId", attempt)
                .put("approvedSourceSha256", sha);
            var result = service.generate(authorizedRequest);
            assertEquals("EVIDENCE_PENDING",result.getString("status"));
            assertEquals("FILE_HASH_VERIFIED_NOT_AUDITED",result.getString("verification"));
            assertTrue(Files.readString(root.resolve(result.getString("documentPath"))).contains("Documents value()"));
            assertNotNull(result.getString("artifactRecordId"));
            assertEquals(64,result.getString("sourceSha256").length());
            assertEquals(64,result.getString("documentSha256").length());
            // Same live attempt must replay the durable artifact, not write another file.
            var replay = service.generate(authorizedRequest);
            assertEquals(Boolean.TRUE, replay.getBoolean("replayed"));
            assertEquals(result.getString("artifactRecordId"), replay.getString("artifactRecordId"));
            assertEquals(result.getString("documentSha256"), replay.getString("documentSha256"));
        } finally {server.stop(0);}
    }
}
