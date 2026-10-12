package dev.aies.control.patch;

import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Parses untrusted agent output into a proposal; does not approve or execute it. */
public final class AgentChangeProposalParser {
    private final ObjectMapper mapper;
    public AgentChangeProposalParser(ObjectMapper mapper) {this.mapper=Objects.requireNonNull(mapper);}
    public List<WorkspaceChangeService.Change> parse(String json) throws Exception {
        if(json==null || json.length()>500_000) throw new IllegalArgumentException("Invalid output size");
        JsonNode root=mapper.readTree(json);
        if(root==null || !root.isObject() || root.size()!=1 || !root.has("changes"))
            throw new IllegalArgumentException("Expected object with changes");
        JsonNode changes=root.path("changes");
        if(!changes.isArray() || changes.isEmpty() || changes.size()>12)
            throw new IllegalArgumentException("Invalid changes");
        List<WorkspaceChangeService.Change> result=new ArrayList<>();
        for(JsonNode change:changes) {
            if(!change.isObject() || change.size()!=3 || !change.hasNonNull("path")
                    || !change.hasNonNull("originalSha256") || !change.hasNonNull("replacement"))
                throw new IllegalArgumentException("Invalid change");
            for(String key:List.of("path","originalSha256","replacement"))
                if(!change.path(key).isTextual()) throw new IllegalArgumentException("Invalid type");
            String sha=change.path("originalSha256").asText();
            if(!sha.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid hash");
            result.add(new WorkspaceChangeService.Change(change.path("path").asText(),sha,
                    change.path("replacement").asText()));
        }
        return List.copyOf(result);
    }
}
