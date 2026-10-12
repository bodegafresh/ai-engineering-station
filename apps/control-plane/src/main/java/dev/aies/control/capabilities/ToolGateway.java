package dev.aies.control.capabilities;

import java.util.Objects;

/** Enforcement boundary for adapters. Credentials must be resolved server-side, never from agent text. */
public final class ToolGateway {
    public interface Adapter { String invoke(String operation, String input) throws Exception; }
    public interface ApprovalVerifier { boolean verify(String approvalReference, String specialist, String capability); }
    public interface AuditSink { void record(String specialist, String capability, String outcome); }
    private final CapabilityPolicy policy;
    private final ApprovalVerifier verifier;
    private final AuditSink audit;
    public ToolGateway(CapabilityPolicy policy, ApprovalVerifier verifier, AuditSink audit) {
        this.policy = Objects.requireNonNull(policy);
        this.verifier = Objects.requireNonNull(verifier);
        this.audit = Objects.requireNonNull(audit);
    }
    public String execute(String specialist, String capability, String operation, String input,
                          String approvalReference, Adapter adapter) throws Exception {
        Objects.requireNonNull(adapter);
        var decision = policy.decide(specialist, capability);
        boolean approved = decision == CapabilityPolicy.Decision.REQUIRE_APPROVAL
                && approvalReference != null && verifier.verify(approvalReference, specialist, capability);
        if (!policy.canExecute(specialist, capability, approved)) {
            audit.record(specialist, capability, "DENIED");
            throw new SecurityException("Capability denied or approval missing");
        }
        audit.record(specialist, capability, "STARTED");
        try {
            String result = adapter.invoke(operation, input);
            audit.record(specialist, capability, "SUCCEEDED");
            return result;
        } catch (Exception e) {
            audit.record(specialist, capability, "FAILED");
            throw e;
        }
    }
}
