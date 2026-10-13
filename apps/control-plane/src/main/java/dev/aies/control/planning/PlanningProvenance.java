package dev.aies.control.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import java.util.*;

/** Exact source-ID/URI/digest mapping for immutable planning evidence; no external-byte claims. */
public final class PlanningProvenance {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final String HEADING = "## Source provenance (intake SHA-256 claims)";
    private PlanningProvenance() {}

    public static String embed(PlanningContracts.Intake intake, PlanningContracts.Plan plan, String content) {
        PlanningContracts.validate(intake);
        Objects.requireNonNull(content);
        var refs = PlanningArtifactEvidence.sourceRefs(intake, plan);
        if (!content.contains("## State") || content.contains(HEADING))
            throw new IllegalArgumentException("Invalid or duplicate provenance section");
        var sources = new ArrayList<Map<String,String>>();
        var used = new LinkedHashSet<String>();
        for (var r : plan.requirements()) used.add(r.sourceId());
        for (var s : intake.sources()) if (used.contains(s.id()))
            sources.add(Map.of("id",s.id(),"uri",s.uri(),"digest",s.digest().toLowerCase(Locale.ROOT)));
        if (sources.size()!=refs.size()) throw new IllegalArgumentException("Missing source mapping");
        try {
            String manifest = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(sources);
            return content.replace("## State", HEADING+"\n\n"+manifest+"\n\n## State");
        } catch (Exception e) { throw new IllegalStateException("Cannot encode provenance",e); }
    }

    public static List<String> findings(CommunicationContracts.Artifact artifact, JsonNode plan) {
        var issues = new ArrayList<String>();
        String content = artifact.content();
        int start = content.indexOf(HEADING);
        int end = content.indexOf("## State",Math.max(0,start+HEADING.length()));
        if (start<0 || end<=start || content.indexOf(HEADING,start+HEADING.length())>=0)
            return List.of("PROVENANCE_SECTION_MISSING_OR_DUPLICATE");
        JsonNode manifest;
        try { manifest=JSON.readTree(content.substring(start+HEADING.length(),end).trim()); }
        catch(Exception e) { return List.of("PROVENANCE_JSON_INVALID"); }
        if (!manifest.isArray() || manifest.isEmpty()) return List.of("PROVENANCE_EMPTY");
        var sourceMap=new LinkedHashMap<String,String>();
        var uriMap=new LinkedHashMap<String,String>();
        for (JsonNode source:manifest) {
            if (!source.isObject() || source.size()!=3) {issues.add("PROVENANCE_SCHEMA_INVALID");continue;}
            String id=field(source,"id"),uri=field(source,"uri"),digest=field(source,"digest");
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}") ||
                !(uri.startsWith("artifact://") || uri.startsWith("https://")) ||
                !digest.matches("[a-f0-9]{64}")) issues.add("PROVENANCE_FIELD_INVALID");
            if (sourceMap.putIfAbsent(id,uri)!=null || uriMap.putIfAbsent(uri,digest)!=null)
                issues.add("PROVENANCE_DUPLICATE");
        }
        var required=new LinkedHashSet<String>();
        for (JsonNode req:plan.path("requirements")) required.add(field(req,"sourceId"));
        if (!sourceMap.keySet().equals(required)) issues.add("PROVENANCE_SOURCE_ID_MISMATCH");
        if (!new LinkedHashSet<>(artifact.sourceRefs()).equals(uriMap.keySet()) ||
            artifact.sourceRefs().size()!=uriMap.size()) issues.add("PROVENANCE_URI_MISMATCH");
        // A digest here is a validated intake claim, NOT verification of original source bytes.
        return List.copyOf(issues);
    }
    private static String field(JsonNode node,String name) {
        JsonNode n=node.path(name);return n.isTextual()?n.asText():"";
    }
}
