package dev.aies.control.science;

import java.util.*;

/** P0.11: descriptive retrospective. Wilson assumes independent Bernoulli trials. */
public final class ScientificRetrospective {
    private ScientificRetrospective() {}
    public record ExperimentMetrics(String experimentId,int trials,int successes,double successRate,
                                    Double wilsonLower95,Double wilsonUpper95,double totalCost,
                                    Double costPerSuccess,double meanDurationSeconds,String recommendation) {}
    public record Report(String workflowId,int sourceCount,int observationCount,
                         List<ExperimentMetrics> experiments,List<String> caveats) {}
    public static Report calculate(String workflowId,List<ResearchEvidenceStore.SourceRecord> sources,
                                   List<ResearchEvidenceStore.Observation> observations) {
        ResearchEvidence.validId(workflowId);
        var groups=new TreeMap<String,List<ResearchEvidenceStore.Observation>>();
        for(var o:observations) {
            if(!workflowId.equals(o.workflowId()))throw new IllegalArgumentException("Cross-workflow observation");
            groups.computeIfAbsent(o.experimentId(),k->new ArrayList<>()).add(o);
        }
        var results=new ArrayList<ExperimentMetrics>();
        for(var entry:groups.entrySet()) {
            var rows=entry.getValue();int n=rows.size(),wins=(int)rows.stream().filter(ResearchEvidenceStore.Observation::success).count();
            double cost=rows.stream().mapToDouble(ResearchEvidenceStore.Observation::cost).sum();
            double mean=rows.stream().mapToDouble(ResearchEvidenceStore.Observation::durationSeconds).average().orElse(0);
            var interval=ScientificStatistics.wilson95(wins,n);
            String recommendation=n<30?"INSUFFICIENT_SAMPLE":"REVIEW_AGAINST_PRE_REGISTERED_BASELINE";
            results.add(new ExperimentMetrics(entry.getKey(),n,wins,(double)wins/n,interval.lower(),interval.upper(),cost,
                    wins==0?null:cost/wins,mean,recommendation));
        }
        for(var source:sources)if(!workflowId.equals(source.source().workflowId()))throw new IllegalArgumentException("Cross-workflow source");
        return new Report(workflowId,sources.size(),observations.size(),List.copyOf(results),
            List.of("Observational summaries only: no causal attribution or guaranteed profitability",
                    "Wilson 95% intervals assume independent Bernoulli observations; correlated trials need a different method",
                    "At least 30 observations is a review threshold, not proof of statistical power",
                    "Compare with pre-registered baseline and include real costs before adopting changes"));
    }
}
