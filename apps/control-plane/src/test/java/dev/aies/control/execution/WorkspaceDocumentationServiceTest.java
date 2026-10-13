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
            var result = service.generate(request("A.java"));
            assertEquals("EVIDENCE_PENDING",result.getString("status"));
            assertEquals("FILE_HASH_VERIFIED_NOT_AUDITED",result.getString("verification"));
            assertTrue(Files.readString(root.resolve(result.getString("documentPath"))).contains("Documents value()"));
            assertNotNull(result.getString("artifactRecordId"));
            assertEquals(64,result.getString("sourceSha256").length());
            assertEquals(64,result.getString("documentSha256").length());
            assertThrows(FileAlreadyExistsException.class, () -> service.generate(request("A.java")));
        } finally {server.stop(0);}
    }
}
