package dev.aies.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Explicitly approved, allowlisted tools. Not an autonomous shell. */
@Path("/api/v1/tools")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class ToolGatewayResource {
    @ConfigProperty(name="aies.tools.enabled", defaultValue="false") boolean enabled;
    @ConfigProperty(name="aies.tools.token", defaultValue="") String token;
    @ConfigProperty(name="aies.tools.workspace", defaultValue=".") String workspace;
    @ConfigProperty(name="aies.tools.python", defaultValue=".aies/venv/bin/python") String python;
    @Inject ObjectMapper mapper;

    public record ToolRequest(String tool, String url, String source, String output, Boolean approved) {}

    @POST @Path("/execute")
    public Response execute(@HeaderParam("X-AIES-Tool-Token") String supplied, ToolRequest req) {
        if (!enabled) return error(403, "tool gateway disabled");
        if (token == null || token.length() < 24 || supplied == null ||
            !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8)))
            return error(401, "invalid tool token");
        if (req == null || !Boolean.TRUE.equals(req.approved())) return error(403, "explicit approval required");
        if (req.tool() == null || req.output() == null) return error(400, "tool and output required");
        if (req.output().length() > 300 || req.output().startsWith("/") || req.output().contains(".."))
            return error(400, "invalid output path");
        List<String> command = new java.util.ArrayList<>();
        java.nio.file.Path root = java.nio.file.Path.of(workspace)
                .toAbsolutePath()
                .normalize();

        java.nio.file.Path py = java.nio.file.Path.of(python);

        if (!py.isAbsolute()) {
            py = root.resolve(py);
        }
        command.add(py.toString());
        command.add(root.resolve("tools/aies_tools.py").toString());
        switch (req.tool()) {
            case "diagram.render" -> {
                if (req.source() == null || req.source().length() > 300) return error(400, "source required");
                command.addAll(List.of("diagram", "--source", req.source(), "--output", req.output()));
            }
            case "browser.inspect" -> {
                if (req.url() == null || req.url().length() > 500) return error(400, "url required");
                command.addAll(List.of("browser", "--url", req.url(), "--output", req.output()));
            }
            default -> { return error(400, "tool not allowlisted"); }
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true);
            pb.environment().put("AIES_WORKSPACE", root.toString());
            Process process = pb.start();
            // Drain output asynchronously so a full pipe cannot block the subprocess.
            var output = new java.io.ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var input = process.getInputStream()) { input.transferTo(new java.io.OutputStream() {
                    int count;
                    @Override public void write(int b) { if (count++ < 65536) output.write(b); }
                }); } catch (Exception ignored) { }
            });
            boolean finished = process.waitFor(55, TimeUnit.SECONDS);
            if (!finished) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); return error(504, "tool timeout"); }
            reader.join(2000);
            String stdout = output.toString(StandardCharsets.UTF_8).trim();
            JsonNode parsed;
            try { parsed = mapper.readTree(stdout); }
            catch (Exception e) { return error(502, "invalid tool response (see server logs)"); }
            if (process.exitValue() != 0 || !parsed.path("ok").asBoolean(false))
                return Response.status(422).entity(parsed).build();
            return Response.ok(parsed).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(500, "tool execution interrupted");
        } catch (Exception e) {
            return error(500, "tool execution failed");
        }
    }
    private Response error(int status, String msg) { return Response.status(status).entity(Map.of("ok",false,"error",msg)).build(); }
}
