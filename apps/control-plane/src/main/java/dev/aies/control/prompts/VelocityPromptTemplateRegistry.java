package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;

import java.io.StringWriter;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Registry for operator-owned Velocity prompt templates.
 *
 * Security:
 * - Only specialists registered in SpecialistCatalog are allowed.
 * - Templates are loaded exclusively from the application classpath.
 * - Arbitrary template paths are rejected.
 * - Required prompt variables must be present and non-blank.
 * - User-provided values are treated as data, not as Velocity templates.
 *
 * The specialist catalog is the single source of truth for allowed IDs.
 */
@ApplicationScoped
public class VelocityPromptTemplateRegistry implements PromptTemplateRegistry {

    private static final Pattern ID =
            Pattern.compile("[a-z][a-z0-9-]{1,63}");

    private static final Pattern VARIABLE_NAME =
            Pattern.compile("[a-zA-Z][a-zA-Z0-9]{0,63}");

    private static final Set<String> REQUIRED = Set.of(
            "objective",
            "projectContext",
            "constraints",
            "acceptanceCriteria",
            "outputContract"
    );

    private static final String TEMPLATE_ROOT =
            "prompts/specialists/";

    private final VelocityEngine engine;
    private final SpecialistCatalog catalog;

    /**
     * Constructor used by CDI.
     */
    @Inject
    public VelocityPromptTemplateRegistry(SpecialistCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog);
        this.engine = createEngine();
        validateTemplates();
    }

    /**
     * Compatibility constructor for existing tests and manual instantiation.
     *
     * Preserves:
     * new VelocityPromptTemplateRegistry()
     */
    public VelocityPromptTemplateRegistry() {
        this(new SpecialistCatalog(new ObjectMapper()));
    }

    private static VelocityEngine createEngine() {
        Properties props = new Properties();

        props.setProperty(
                RuntimeConstants.RESOURCE_LOADERS,
                "class"
        );

        props.setProperty(
                "resource.loader.class.class",
                ClasspathResourceLoader.class.getName()
        );

        props.setProperty(
                RuntimeConstants.RUNTIME_REFERENCES_STRICT,
                "true"
        );

        props.setProperty(
                "velocimacro.inline.allow",
                "false"
        );

        props.setProperty(
                "velocimacro.inline.replace_global",
                "false"
        );

        props.setProperty(
                "directive.parse.max_depth",
                "4"
        );

        VelocityEngine velocityEngine = new VelocityEngine(props);
        velocityEngine.init();

        return velocityEngine;
    }

    /**
     * Returns all specialist IDs declared in the catalog.
     *
     * The returned set is immutable.
     */
    @Override
    public Set<String> availableTemplates() {
        return Set.copyOf(catalog.ids());
    }

    /**
     * Validates all catalog entries at startup.
     *
     * Every specialist must have an associated classpath template.
     */
    private void validateTemplates() {
        for (String specialistId : catalog.ids()) {
            validateTemplateId(specialistId);

            String templatePath = templatePath(specialistId);

            if (!engine.resourceExists(templatePath)) {
                throw new IllegalStateException(
                        "Missing Velocity template for specialist: "
                                + specialistId
                                + " ("
                                + templatePath
                                + ")"
                );
            }
        }
    }

    /**
     * Renders a registered specialist template.
     *
     * @param templateId registered specialist ID
     * @param variables prompt context variables
     * @return rendered prompt
     */
    @Override
    public String render(
            String templateId,
            Map<String, String> variables
    ) {
        validateTemplateId(templateId);
        validateVariables(variables);

        VelocityContext context = new VelocityContext();

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = entry.getKey();

            if (key == null || !VARIABLE_NAME.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Invalid prompt variable name: " + key
                );
            }

            context.put(
                    key,
                    entry.getValue() == null ? "" : entry.getValue()
            );
        }

        StringWriter writer = new StringWriter();

        engine.mergeTemplate(
                templatePath(templateId),
                "UTF-8",
                context,
                writer
        );

        String rendered = writer.toString();

        if (rendered.isBlank()) {
            throw new IllegalStateException(
                    "Rendered prompt is empty for specialist: "
                            + templateId
            );
        }

        return rendered;
    }

    /**
     * Rejects unknown specialists and unsafe identifiers.
     */
    private void validateTemplateId(String templateId) {
        if (templateId == null
                || !ID.matcher(templateId).matches()
                || !catalog.ids().contains(templateId)) {

            throw new IllegalArgumentException(
                    "Unknown or unsafe template ID: " + templateId
            );
        }
    }

    /**
     * Validates mandatory prompt context.
     */
    private static void validateVariables(
            Map<String, String> variables
    ) {
        if (variables == null) {
            throw new IllegalArgumentException(
                    "Prompt variables cannot be null"
            );
        }

        for (String required : REQUIRED) {
            String value = variables.get(required);

            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Missing or empty required prompt variable: "
                                + required
                );
            }
        }
    }

    /**
     * Constructs a safe, fixed-root classpath template path.
     */
    private static String templatePath(String specialistId) {
        return TEMPLATE_ROOT + specialistId + ".vm";
    }
}
