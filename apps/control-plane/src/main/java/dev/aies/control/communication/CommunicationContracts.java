package dev.aies.control.communication;

import java.util.*;
import java.util.regex.Pattern;

/** P0.4: versioned internal contracts. No execution or project mutation. */
public final class CommunicationContracts {
    private CommunicationContracts() {}
    public static final String VERSION = "1.0";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    public enum MessageType { TASK_REQUEST, TASK_RESULT, REVIEW_REQUEST, REVIEW_RESULT, CLARIFICATION }
    public enum AuditDecision { APPROVED, REVISION_REQUIRED, BLOCKED }
    public record Criterion(String id, String given, String when, String then) {}
    public record Message(String schemaVersion, String workflowId, String taskId,
                          String producer, String consumer, MessageType type,
                          String objective, List<String> contextRefs, List<String> instructions,
                          List<String> constraints, List<String> expectedOutputs,
                          List<Criterion> acceptanceCriteria) {}
    public record Artifact(String schemaVersion, String workflowId, String taskId,
                           String producer, String kind, String contentType, String content,
                           List<String> sourceRefs) {}
    public record Audit(String schemaVersion, String workflowId, String subjectId,
                        String auditor, AuditDecision decision, int iteration,
                        int maxIterations, List<String> findings, List<String> recommendations) {}
    public static void id(String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw new IllegalArgumentException("Invalid identifier");
    }
    private static String required(String value, int max) {
        if(value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("Invalid required field");
        return value;
    }
    private static List<String> strings(List<String> input, int maxItems) {
        if(input==null || input.size()>maxItems) throw new IllegalArgumentException("Invalid list");
        for(String s:input) required(s,4096);
        return List.copyOf(input);
    }
    private static void version(String value) {
        if(!VERSION.equals(value)) throw new IllegalArgumentException("Unsupported schema version");
    }
    public static Message validate(Message m) {
        Objects.requireNonNull(m);
        version(m.schemaVersion()); id(m.workflowId()); id(m.taskId());
        id(m.producer()); id(m.consumer()); Objects.requireNonNull(m.type());
        required(m.objective(),12000);
        List<String> refs=strings(m.contextRefs(),32);
        for(String ref:refs) if(!ref.matches("artifact://[A-Za-z0-9._/-]{1,250}")) throw new IllegalArgumentException("Invalid artifact ref");
        List<String> instructions=strings(m.instructions(),32);
        if(instructions.isEmpty()) throw new IllegalArgumentException("Instructions required");
        List<String> constraints=strings(m.constraints(),32);
        List<String> outputs=strings(m.expectedOutputs(),32);
        if(outputs.isEmpty()) throw new IllegalArgumentException("Expected outputs required");
        if(m.acceptanceCriteria()==null || m.acceptanceCriteria().size()>32) throw new IllegalArgumentException("Invalid criteria");
        Set<String> seen=new HashSet<>();
        List<Criterion> criteria=new ArrayList<>();
        for(Criterion c:m.acceptanceCriteria()) {
            Objects.requireNonNull(c);id(c.id());
            if(!seen.add(c.id())) throw new IllegalArgumentException("Duplicate criterion");
            criteria.add(new Criterion(c.id(),required(c.given(),4096),required(c.when(),4096),required(c.then(),4096)));
        }
        return new Message(VERSION,m.workflowId(),m.taskId(),m.producer(),m.consumer(),m.type(),
                m.objective(),refs,instructions,constraints,outputs,List.copyOf(criteria));
    }
    public static Artifact validate(Artifact a) {
        Objects.requireNonNull(a);version(a.schemaVersion());id(a.workflowId());id(a.taskId());id(a.producer());id(a.kind());
        String type=required(a.contentType(),120);
        if(!Set.of("text/markdown","text/plain","application/json").contains(type)) throw new IllegalArgumentException("Unsupported content type");
        required(a.content(),262144);
        return new Artifact(VERSION,a.workflowId(),a.taskId(),a.producer(),a.kind(),type,a.content(),strings(a.sourceRefs(),64));
    }
    public static Audit validate(Audit a) {
        Objects.requireNonNull(a);version(a.schemaVersion());id(a.workflowId());id(a.subjectId());id(a.auditor());
        Objects.requireNonNull(a.decision());
        if(a.maxIterations()<1 || a.maxIterations()>10 || a.iteration()<1 || a.iteration()>a.maxIterations())
            throw new IllegalArgumentException("Invalid iteration bounds");
        List<String> findings=strings(a.findings(),64);
        List<String> recommendations=strings(a.recommendations(),64);
        if(a.decision()!=AuditDecision.APPROVED && findings.isEmpty()) throw new IllegalArgumentException("Findings required");
        if(a.decision()==AuditDecision.REVISION_REQUIRED && a.iteration()==a.maxIterations())
            throw new IllegalArgumentException("Iteration limit exhausted; use BLOCKED");
        return new Audit(VERSION,a.workflowId(),a.subjectId(),a.auditor(),a.decision(),
                a.iteration(),a.maxIterations(),findings,recommendations);
    }
}
