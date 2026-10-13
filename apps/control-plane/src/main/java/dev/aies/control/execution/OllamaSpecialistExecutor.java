package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.json.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Real local-model inference, no shell/filesystem/network tools exposed to the model. */
public final class OllamaSpecialistExecutor {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    public OllamaSpecialistExecutor(HttpClient client, ObjectMapper mapper, String baseUrl, String model, int timeoutSeconds) {
        this.client = Objects.requireNonNull(client);
        this.mapper = Objects.requireNonNull(mapper);
        URI base = URI.create(Objects.requireNonNull(baseUrl));
        if (!"http".equals(base.getScheme()) || base.getHost() == null ||
            !(base.getHost().equals("localhost") || base.getHost().equals("127.0.0.1") || base.getHost().equals("::1")) ||
            base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null ||
            !base.getPath().matches("/?")) throw new IllegalArgumentException("Only local Ollama base URLs allowed");
        this.endpoint = base.resolve("/api/generate");
        if (model == null || !model.matches("[a-zA-Z0-9._:/-]{1,128}")) throw new IllegalArgumentException("Invalid model");
        if (timeoutSeconds < 2 || timeoutSeconds > 600) throw new IllegalArgumentException("Invalid timeout");
        this.model = model;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }
    public JsonObject execute(JsonObject request) throws Exception {
        String prompt = RealSpecialistPolicy.prompt(request);
        String json = mapper.writeValueAsString(Map.of("model", model, "prompt", prompt,
            "stream", false, "options", Map.of("num_predict", 1200, "temperature", 0.2)));
        HttpRequest http = HttpRequest.newBuilder(endpoint).timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = client.send(http, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Local model HTTP " + response.statusCode());
        if (response.body().length() > 524288) throw new IllegalStateException("Model response too large");
        JsonNode result = mapper.readTree(response.body());
        if (result.path("error").isTextual()) throw new IllegalStateException("Local model error");
        String output = result.path("response").asText("").trim();
        if (output.isEmpty() || output.length() > 262144) throw new IllegalStateException("Empty or oversized model output");
        return new JsonObject().put("executed", true).put("content", output)
            .put("attemptId", request.getString("attemptId"))
            .put("executionType", "local-model-inference")
            .put("verification", "UNVERIFIED_MODEL_OUTPUT");
    }
}
