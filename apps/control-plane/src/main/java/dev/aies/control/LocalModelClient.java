package dev.aies.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Minimal OpenAI-compatible local chat adapter; no external API keys or network discovery. */
@ApplicationScoped
public class LocalModelClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;
    public record Completion(String model, String content) {}

    public LocalModelClient(ObjectMapper mapper,
      @ConfigProperty(name="aies.model.base-url",defaultValue="http://127.0.0.1:8081") String baseUrl,
      @ConfigProperty(name="aies.model.name",defaultValue="local") String model,
      @ConfigProperty(name="aies.model.timeout-seconds",defaultValue="90") long timeoutSeconds) {
        this.mapper=mapper;
        URI uri=URI.create(baseUrl);
        String host=uri.getHost();
        if(uri.getUserInfo()!=null || !"http".equals(uri.getScheme()) ||
           !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || "::1".equals(host)))
            throw new IllegalArgumentException("v0.3 only allows local HTTP model servers");
        this.endpoint=uri.resolve(baseUrl.endsWith("/")?"v1/chat/completions":"/v1/chat/completions");
        this.model=model;
        if(timeoutSeconds<1 || timeoutSeconds>600) throw new IllegalArgumentException("Invalid model timeout");
        this.timeout=Duration.ofSeconds(timeoutSeconds);
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }
    public Completion complete(String objective) throws Exception {
        var payload=Map.of("model",model,"stream",false,"temperature",0.2,
          "messages",List.of(
            Map.of("role","system","content","Eres un ingeniero de software. Responde en español. Si se pide código, entrégalo como texto y explica cómo verificarlo. No afirmes haber ejecutado herramientas, pruebas ni cambios en archivos."),
            Map.of("role","user","content",objective)));
        HttpRequest request=HttpRequest.newBuilder(endpoint)
          .timeout(timeout).header("Content-Type","application/json")
          .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("Local model returned HTTP "+response.statusCode());
        JsonNode root=mapper.readTree(response.body());
        JsonNode content=root.path("choices").path(0).path("message").path("content");
        if(!content.isTextual() || content.asText().isBlank()) throw new IllegalStateException("Empty or invalid model response");
        String usedModel=root.path("model").asText(model);
        return new Completion(usedModel,content.asText());
    }
}
