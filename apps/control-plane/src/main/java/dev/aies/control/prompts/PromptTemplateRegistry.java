package dev.aies.control.prompts;

import java.util.Map;
import java.util.Set;

/** Stable abstraction: orchestration never depends on Velocity classes. */
public interface PromptTemplateRegistry {
    String render(String templateId, Map<String, String> variables);
    Set<String> availableTemplates();
}
