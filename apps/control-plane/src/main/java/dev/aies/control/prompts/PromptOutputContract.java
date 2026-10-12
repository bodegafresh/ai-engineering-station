package dev.aies.control.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/** Shape validation, not semantic correctness or authorization. */
public final class PromptOutputContract {
    private static final Set<String> STATUSES = Set.of("PROPOSED", "BLOCKED", "READY_FOR_REVIEW", "COMPLETED");
    private PromptOutputContract() {}
    public static void validate(ObjectMapper mapper, String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Output must be JSON object");
        for (String field : new String[]{"objectiveId", "specialist", "status", "summary", "evidence", "changedPaths", "risks"}) {
            if (!root.has(field)) throw new IllegalArgumentException("Missing field: " + field);
        }
        if (!STATUSES.contains(root.path("status").asText())) throw new IllegalArgumentException("Invalid status");
        if (!root.path("objectiveId").isTextual() || root.path("objectiveId").asText().isBlank() ||
            !root.path("specialist").isTextual() || root.path("specialist").asText().isBlank() ||
            !root.path("summary").isTextual()) throw new IllegalArgumentException("Invalid field types");
        for (String array : new String[]{"evidence", "changedPaths", "risks"}) {
            if (!root.path(array).isArray()) throw new IllegalArgumentException("Expected array: " + array);
            for (JsonNode item : root.path(array)) if (!item.isTextual()) throw new IllegalArgumentException("Expected string in " + array);
        }
    }
}
