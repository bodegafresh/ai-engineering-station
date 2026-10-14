package dev.aies.control.specialists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** S1-A: strictly validated, canonical, inert specialist metadata. No tool grants. */
public final class SpecialistRegistryManifest {
    private SpecialistRegistryManifest() {}
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9.-]{1,127}");
    private static final Pattern VERSION = Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    private static final Set<String> ROLES = Set.of("COORDINATOR","ANALYST","EXECUTOR","RESEARCHER","REVIEWER","AUDITOR");
    private static final Set<String> PROFICIENCY = Set.of("FOUNDATIONAL","INTERMEDIATE","ADVANCED","EXPERT");
    private static final Set<String> KEYS = Set.of("schemaVersion","kind","id","version","displayName","discipline","specializations","competencies","roles","jurisdictions","methodologyRefs","outputContractRefs","requiredTools","governanceRef","maxConcurrent","costUnits");
    private static final Set<String> COMPETENCY_KEYS = Set.of("capabilityId","capabilityVersion","requiredProficiency");
    public record Validated(String id,String version,String canonicalJson,String sha256) {}

    public static Validated validate(ObjectMapper mapper,String json) throws Exception {
        Objects.requireNonNull(mapper);Objects.requireNonNull(json);
        if(json.getBytes(StandardCharsets.UTF_8).length>65536)throw new IllegalArgumentException("Manifest too large");
        JsonNode node=mapper.readTree(json);
        if(node==null||!node.isObject())throw new IllegalArgumentException("Manifest must be object");
        rejectUnknown(node,KEYS);
        for(String required:List.of("schemaVersion","kind","id","version","displayName","discipline","competencies","roles","requiredTools","maxConcurrent","costUnits"))if(!node.has(required))throw new IllegalArgumentException("Missing field: "+required);
        equal(node,"schemaVersion","2.0");equal(node,"kind","SpecialistProfile");
        String id=identifier(node,"id"), version=version(node,"version");
        bounded(node,"displayName",200);identifier(node,"discipline");
        strings(node,"specializations",0,32,true);
        strings(node,"roles",1,8,true).forEach(v->{if(!ROLES.contains(v))throw new IllegalArgumentException("Invalid role");});
        strings(node,"jurisdictions",0,32,true).forEach(v->{if(!v.matches("[A-Z]{2}"))throw new IllegalArgumentException("Invalid jurisdiction");});
        strings(node,"methodologyRefs",0,32,true);
        strings(node,"outputContractRefs",0,32,true);
        strings(node,"requiredTools",0,32,true);
        // Optional in the S0 contract; validate strictly when explicitly supplied.
        if (node.has("governanceRef")) bounded(node,"governanceRef",256);
        integer(node,"maxConcurrent",1,64);integer(node,"costUnits",0,100000);
        JsonNode competencies=node.path("competencies");
        if(!competencies.isArray()||competencies.size()<1||competencies.size()>32)throw new IllegalArgumentException("Invalid competencies");
        Set<String> unique=new HashSet<>();
        for(JsonNode c:competencies){
            if(!c.isObject())throw new IllegalArgumentException("Invalid competency");
            rejectUnknown(c,COMPETENCY_KEYS);
            String capability=identifier(c,"capabilityId"), v=version(c,"capabilityVersion");
            String proficiency=bounded(c,"requiredProficiency",32);
            if(!PROFICIENCY.contains(proficiency)||!unique.add(capability+"@"+v))throw new IllegalArgumentException("Duplicate or invalid competency");
        }
        String canonical=mapper.writeValueAsString(sorted(mapper,node));
        var digest=MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return new Validated(id,version,canonical,HexFormat.of().formatHex(digest));
    }
    private static JsonNode sorted(ObjectMapper mapper,JsonNode n){
        if(n.isObject()){
            ObjectNode o=mapper.createObjectNode();
            var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);
            for(String k:keys)o.set(k,sorted(mapper,n.get(k)));
            return o;
        }
        if(n.isArray()){
            ArrayNode a=mapper.createArrayNode();n.forEach(x->a.add(sorted(mapper,x)));return a;
        }
        return n.deepCopy();
    }
    private static void rejectUnknown(JsonNode n,Set<String> allowed){
        n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown field: "+k);});
    }
    private static String bounded(JsonNode n,String key,int max){
        JsonNode v=n.get(key);
        if(v==null||!v.isTextual()||v.textValue().isBlank()||v.textValue().length()>max)throw new IllegalArgumentException("Invalid field: "+key);
        return v.textValue();
    }
    private static String identifier(JsonNode n,String key){
        String v=bounded(n,key,128);if(!ID.matcher(v).matches())throw new IllegalArgumentException("Invalid identifier: "+key);return v;
    }
    private static String version(JsonNode n,String key){
        String v=bounded(n,key,64);if(!VERSION.matcher(v).matches())throw new IllegalArgumentException("Invalid version: "+key);return v;
    }
    private static void equal(JsonNode n,String key,String expected){if(!expected.equals(bounded(n,key,128)))throw new IllegalArgumentException("Invalid "+key);}
    private static void integer(JsonNode n,String key,int min,int max){JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<min||v.intValue()>max)throw new IllegalArgumentException("Invalid integer: "+key);}
    private static List<String> strings(JsonNode n,String key,int min,int max,boolean unique){
        JsonNode v=n.get(key);if(v==null&&!n.has(key))return List.of();
        if(!v.isArray()||v.size()<min||v.size()>max)throw new IllegalArgumentException("Invalid array: "+key);
        List<String> result=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(JsonNode item:v){if(!item.isTextual()||item.textValue().isBlank()||item.textValue().length()>128)throw new IllegalArgumentException("Invalid item: "+key);
            if(unique&&!seen.add(item.textValue()))throw new IllegalArgumentException("Duplicate: "+key);result.add(item.textValue());}
        return List.copyOf(result);
    }
}
