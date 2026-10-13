package dev.aies.control.communication;

/** Stage-aware semantic expectations; existing artifact schema remains 1.0. */
public final class AuditStagePolicy {
    private AuditStagePolicy() {}
    public enum Stage { PLAN, REQUIREMENTS_PLAN, EXPERIMENT_REPORT, SOFTWARE_IMPLEMENTATION, BUSINESS_VALIDATION, GENERAL }
    public static Stage classify(CommunicationStore.StoredRecord record) {
        if (record.payload() instanceof CommunicationContracts.Artifact a) {
            String kind = a.kind().toLowerCase(java.util.Locale.ROOT);
            if (kind.equals("planning-plan") || kind.equals("requirements-plan")) return Stage.REQUIREMENTS_PLAN;
            if (kind.contains("plan") || kind.contains("hypothesis") || kind.contains("proposal")) return Stage.PLAN;
            if (kind.contains("experiment") || kind.contains("result") || kind.contains("evidence")) return Stage.EXPERIMENT_REPORT;
            if (kind.contains("implementation") || kind.contains("code")) return Stage.SOFTWARE_IMPLEMENTATION;
            if (kind.contains("business") || kind.contains("market")) return Stage.BUSINESS_VALIDATION;
        }
        return Stage.GENERAL;
    }
    public static String rubric(Stage stage) {
        return switch(stage) {
            case REQUIREMENTS_PLAN -> "This is a UNIVERSAL PRE-EXECUTION REQUIREMENTS PLAN. Assess source traceability, clear acceptance criteria, dependency DAG, specialist assignments, budget, risks and unresolved questions. Do not demand null hypotheses, control groups or experimental results for non-scientific work. Do not claim implementation has been executed.";
            case PLAN -> "This is a PRE-EXPERIMENT PLAN. Assess falsifiable hypotheses, null hypothesis, controls, measurable outcomes, budget, method and decision criteria. DO NOT require experimental results yet. Approval permits planning to proceed, not claims of validation.";
            case EXPERIMENT_REPORT -> "This is a POST-EXPERIMENT REPORT. Require actual observed data, provenance, sample sizes, uncertainty, limitations, reproducibility and explicit decision rules. Reject unsupported success claims.";
            case SOFTWARE_IMPLEMENTATION -> "This is a SOFTWARE IMPLEMENTATION. Require traceability to requirements and concrete reproducible test evidence; do not accept code descriptions as proof of passing tests.";
            case BUSINESS_VALIDATION -> "This is a BUSINESS VALIDATION REPORT. Distinguish hypotheses from observed customer demand, revenue, costs and uncertainty. Reject unverified financial outcomes.";
            case GENERAL -> "Assess internal consistency, clear acceptance criteria, provenance where claims require evidence and no fabricated results. Do not assume every document is a final report.";
        };
    }
}
