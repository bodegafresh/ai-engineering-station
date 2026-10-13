package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OllamaSpecialistExecutorTest {
    private JsonObject request() {
        return new JsonObject().put("workflowId","wf-1").put("taskId","task-1")
            .put("attemptId","attempt-1").put("specialistId","researcher")
            .put("capability","research").put("objective","Describe a Java component");
    }
    @Test void performsActualHttpInferenceAndPreservesAttempt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate", exchange -> {
            assertEquals("POST",exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("Describe a Java component"));
            byte[] response = "{\"response\":\"# Component\\nActual generated text\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
        });
        server.start();
        try {
            var executor = new OllamaSpecialistExecutor(HttpClient.newHttpClient(),new ObjectMapper(),
                "http://127.0.0.1:"+server.getAddress().getPort(),"qwen3:4b",5);
            var result=executor.execute(request());
            assertTrue(result.getBoolean("executed"));
            assertEquals("attempt-1",result.getString("attemptId"));
            assertEquals("UNVERIFIED_MODEL_OUTPUT",result.getString("verification"));
            assertTrue(result.getString("content").contains("Actual generated text"));
        } finally {server.stop(0);}
    }
}
