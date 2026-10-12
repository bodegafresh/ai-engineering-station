package dev.aies.control.prompts;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import java.io.StringWriter;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Classpath-only, operator-owned templates. No arbitrary template paths. */
@ApplicationScoped
public class VelocityPromptTemplateRegistry implements PromptTemplateRegistry {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private static final Set<String> REQUIRED = Set.of("objective", "projectContext", "constraints", "acceptanceCriteria", "outputContract");
    private static final Set<String> ALLOWED = Set.of("router", "planner", "architect", "backend-developer", "frontend-developer", "qa-engineer", "security-engineer", "devops-engineer", "ux-ui-designer", "native-developer", "image-specialist", "video-specialist", "feasibility-reviewer", "acceptance-reviewer", "retrospective");
    private final VelocityEngine engine;

    public VelocityPromptTemplateRegistry() {
        var props = new java.util.Properties();
        props.setProperty(RuntimeConstants.RESOURCE_LOADERS, "class");
        props.setProperty("resource.loader.class.class", ClasspathResourceLoader.class.getName());
        props.setProperty(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "true");
        props.setProperty("velocimacro.inline.allow", "false");
props.setProperty("velocimacro.inline.replace_global", "false");
        props.setProperty("directive.parse.max_depth", "4");
        engine = new VelocityEngine(props);
        engine.init();
    }

    @Override public Set<String> availableTemplates() { return ALLOWED; }

    @Override public String render(String templateId, Map<String,String> variables) {
        if (templateId == null || !ID.matcher(templateId).matches() || !ALLOWED.contains(templateId))
            throw new IllegalArgumentException("Unknown or unsafe template ID");
        if (variables == null || !variables.keySet().containsAll(REQUIRED))
            throw new IllegalArgumentException("Missing required prompt variables: " + REQUIRED);
        for (String key : REQUIRED) {
            if (variables.get(key) == null || variables.get(key).isBlank())
                throw new IllegalArgumentException("Empty required prompt variable: " + key);
        }
        // Velocity references are evaluated in template source only. User content is data, not parsed again.
        var context = new VelocityContext();
        for (var entry : variables.entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z][a-zA-Z0-9]{0,63}"))
                throw new IllegalArgumentException("Invalid variable name");
            context.put(entry.getKey(), entry.getValue() == null ? "" : entry.getValue());
        }
        var writer = new StringWriter();
        engine.mergeTemplate("prompts/specialists/" + templateId + ".vm", "UTF-8", context, writer);
        return writer.toString();
    }
}
