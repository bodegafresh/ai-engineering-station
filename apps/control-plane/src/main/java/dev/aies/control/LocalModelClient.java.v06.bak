package dev.aies.control;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** v0.7: local-first, opt-in cloud routing; no tools, retries, or automatic cloud fallback. */
@ApplicationScoped
public class LocalModelClient {
    public record Completion(String model, String content) {}
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Duration timeout;
    private final String localBase;
    private final String localModel;
    private final String defaultProvider;
    private final boolean cloudEnabled;
    private final int maxOutputTokens;

    @Inject
    public LocalModelClient(ObjectMapper mapper,
      @ConfigProperty(name="aies.model.base-url", defaultValue="http://127.0.0.1:8081") String localBase,
      @ConfigProperty(name="aies.model.name", defaultValue="local") String localModel,
      @ConfigProperty(name="aies.model.timeout-seconds", defaultValue="90") long timeoutSeconds,
      @ConfigProperty(name="aies.ai.default-provider", defaultValue="local") String defaultProvider,
      @ConfigProperty(name="aies.ai.cloud-enabled", defaultValue="false") boolean cloudEnabled,
      @ConfigProperty(name="aies.ai.max-output-tokens", defaultValue="512") int maxOutputTokens) {
        this.mapper=mapper;
        this.localBase=localBase;
        this.localModel=localModel;
        this.defaultProvider=defaultProvider.toLowerCase(Locale.ROOT).trim();
        this.cloudEnabled=cloudEnabled;
        if(timeoutSeconds<1 || timeoutSeconds>600) throw new IllegalArgumentException("Invalid timeout");
        if(maxOutputTokens<1 || maxOutputTokens>8192) throw new IllegalArgumentException("Invalid output token limit");
        if(!Set.of("local","openai","anthropic","deepseek","qwen","moonshot","zhipu").contains(this.defaultProvider))
            throw new IllegalArgumentException("Unsupported default provider");
        this.timeout=Duration.ofSeconds(timeoutSeconds);
        this.maxOutputTokens=maxOutputTokens;
        this.client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        validateLocal(localBase);
    }

    /**
     * Constructor de compatibilidad con v0.6.
     * Mantiene los tests existentes sin habilitar servicios cloud.
     */
    public LocalModelClient(
            ObjectMapper mapper,
            String baseUrl,
            String model,
            long timeoutSeconds
    ) {
        this(
            mapper,
            baseUrl,
            model,
            timeoutSeconds,
            "local",
            false,
            512
        );
    }

    public Completion complete(String objective) throws Exception {
        // Provider is configured by the operator, never selected by untrusted task text.
        String provider=defaultProvider;
        if(!provider.equals("local") && !cloudEnabled)
            throw new IllegalStateException("Cloud inference disabled: set AIES_AI_CLOUD_ENABLED=true explicitly");
        return provider.equals("anthropic") ? anthropic(objective) : openAiCompatible(provider, objective);
    }

    private Completion openAiCompatible(String provider, String objective) throws Exception {
        String base, model, key="";
        switch(provider) {
            case "local" -> {base=localBase;model=localModel;}
            case "openai" -> {base="https://api.openai.com/v1";model=env("AIES_OPENAI_MODEL","gpt-4.1-mini");key=secret("OPENAI_API_KEY");}
            case "deepseek" -> {base="https://api.deepseek.com";model=env("AIES_DEEPSEEK_MODEL","deepseek-chat");key=secret("DEEPSEEK_API_KEY");}
            case "qwen" -> {base="https://dashscope-intl.aliyuncs.com/compatible-mode/v1";model=env("AIES_QWEN_MODEL","qwen-plus");key=secret("DASHSCOPE_API_KEY");}
            case "moonshot" -> {base="https://api.moonshot.ai/v1";model=env("AIES_MOONSHOT_MODEL","kimi-k2-turbo-preview");key=secret("MOONSHOT_API_KEY");}
            case "zhipu" -> {base="https://open.bigmodel.cn/api/paas/v4";model=env("AIES_ZHIPU_MODEL","glm-4-flash");key=secret("ZHIPU_API_KEY");}
            default -> throw new IllegalArgumentException("Unsupported provider");
        }
        URI endpoint=chatEndpoint(base);
        var payload=Map.of("model",model,"stream",false,"temperature",0.2,"max_tokens",maxOutputTokens,
          "messages",List.of(Map.of("role","system","content",systemPrompt()),Map.of("role","user","content",objective)));
        var builder=HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type","application/json");
        if(!key.isBlank()) builder.header("Authorization","Bearer "+key);
        HttpResponse<String> response=client.send(builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(),provider);
        JsonNode root=mapper.readTree(response.body());
        String content=root.path("choices").path(0).path("message").path("content").asText("");
        if(content.isBlank()) throw new IllegalStateException("Empty model response from "+provider);
        return new Completion(provider+":"+root.path("model").asText(model),content);
    }

    private Completion anthropic(String objective) throws Exception {
        String model=env("AIES_ANTHROPIC_MODEL","claude-sonnet-4-5");
        String key=secret("ANTHROPIC_API_KEY");
        var payload=Map.of("model",model,"max_tokens",maxOutputTokens,"system",systemPrompt(),
            "messages",List.of(Map.of("role","user","content",objective)));
        var req=HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
            .timeout(timeout).header("Content-Type","application/json")
            .header("x-api-key",key).header("anthropic-version","2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
        var response=client.send(req,HttpResponse.BodyHandlers.ofString());
        requireSuccess(response.statusCode(),"anthropic");
        JsonNode root=mapper.readTree(response.body());
        StringBuilder content=new StringBuilder();
        for(JsonNode block:root.path("content")) if("text".equals(block.path("type").asText())) content.append(block.path("text").asText());
        if(content.isEmpty()) throw new IllegalStateException("Empty Anthropic response");
        return new Completion("anthropic:"+root.path("model").asText(model),content.toString());
    }

    private static String systemPrompt(){return "Eres un ingeniero de software. Responde en español. No afirmes haber ejecutado herramientas o pruebas. Nunca reveles secretos.";}
    private static String env(String name,String fallback){String value=System.getenv(name);return value==null||value.isBlank()?fallback:value.trim();}
    private static String secret(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing provider credential: "+name);return value.trim();}
    private static void requireSuccess(int status,String provider){if(status!=200)throw new IllegalStateException("Provider "+provider+" returned HTTP "+status+" (body redacted)");}
    private static void validateLocal(String base){
        URI u=URI.create(base);String host=u.getHost();
        if(!"http".equals(u.getScheme())||u.getUserInfo()!=null||host==null||
           !("localhost".equalsIgnoreCase(host)||"127.0.0.1".equals(host)||"::1".equals(host)))
            throw new IllegalArgumentException("Local provider must use loopback HTTP");
        if(u.getQuery()!=null||u.getFragment()!=null)throw new IllegalArgumentException("Invalid local base URL");
    }
    private static URI chatEndpoint(String base){
        String trimmed=base.replaceAll("/+$","");
        return URI.create(trimmed+(trimmed.endsWith("/v1")?"/chat/completions":"/v1/chat/completions"));
    }
}
