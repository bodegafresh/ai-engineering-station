package dev.aies.control.planning;

import java.util.*;
import dev.aies.control.communication.CommunicationContracts;

/** P0.14: explicit, universal requirements intake; no synthetic evidence. */
public final class PlanningContracts {
    private PlanningContracts() {}
    public static final String VERSION="1.0";
    public enum Verification { TEST, ANALYSIS, INSPECTION, DEMONSTRATION }
    public record Source(String id, String uri, String digest, String excerpt) {}
    public record Requirement(String id, String statement, String sourceId, Verification verification,
                              String given, String when, String then, String capability,
                              List<String> dependsOn, boolean critical) {}
    public record Intake(String schemaVersion,String workflowId,String objective,String domain,
                         List<Source> sources,List<Requirement> requirements,List<String> constraints,
                         List<String> openQuestions,int budgetUnits) {}
    public record Task(String id,String requirementId,String capability,List<String> dependencies,
                       String specialistId,int specialistCost,String objective,
                       String given,String when,String then,Verification verification) {}
    public record Plan(String workflowId,String objective,String domain,List<Requirement> requirements,
                       List<Task> tasks,List<String> openQuestions,int budgetUnits,
                       int estimatedCostUnits,String status) {}
    static String id(String value) {CommunicationContracts.id(value);return value;}
    static String required(String value,int max) {
        if(value==null||value.isBlank()||value.length()>max) throw new IllegalArgumentException("Missing or oversized field");
        return value.strip();
    }
    static <T> List<T> list(List<T> v,int max) {
        if(v==null||v.size()>max||v.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Invalid list");
        return List.copyOf(v);
    }
    public static Intake validate(Intake input) {
        Objects.requireNonNull(input);
        if(!VERSION.equals(input.schemaVersion()))throw new IllegalArgumentException("Invalid version");
        id(input.workflowId());required(input.objective(),12000);id(input.domain());
        if(input.budgetUnits()<0||input.budgetUnits()>100000)throw new IllegalArgumentException("Invalid budget");
        var sources=list(input.sources(),100);
        var requirements=list(input.requirements(),100);
        if(requirements.isEmpty())throw new IllegalArgumentException("At least one requirement required");
        var ids=new HashSet<String>();
        for(var s:sources) {
            if(!ids.add(id(s.id())))throw new IllegalArgumentException("Duplicate source");
            required(s.uri(),2048);required(s.digest(),128);required(s.excerpt(),4096);
            if(!s.digest().matches("[a-fA-F0-9]{64}"))throw new IllegalArgumentException("SHA-256 required");
            if(!s.uri().startsWith("artifact://")&&!s.uri().startsWith("https://"))
                throw new IllegalArgumentException("Only artifact and HTTPS source references allowed");
        }
        var reqIds=new HashSet<String>();
        for(var r:requirements) {
            if(!reqIds.add(id(r.id())))throw new IllegalArgumentException("Duplicate requirement");
            required(r.statement(),4096);id(r.sourceId());
            if(!ids.contains(r.sourceId()))throw new IllegalArgumentException("Missing source");
            Objects.requireNonNull(r.verification());
            required(r.given(),4096);required(r.when(),4096);required(r.then(),4096);
            id(r.capability());list(r.dependsOn(),50);
        }
        for(var r:requirements)for(var dep:r.dependsOn())
            if(!reqIds.contains(dep)||dep.equals(r.id()))throw new IllegalArgumentException("Unknown/self dependency");
        for(var c:list(input.constraints(),50))required(c,4096);
        for(var q:list(input.openQuestions(),50))required(q,4096);
        return new Intake(VERSION,input.workflowId(),input.objective(),input.domain(),sources,requirements,
                          List.copyOf(input.constraints()),List.copyOf(input.openQuestions()),input.budgetUnits());
    }
}
