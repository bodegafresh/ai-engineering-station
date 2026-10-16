package dev.aies.control.execution;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * H02-D2C2f (slice 1): deterministic, side-effect-free authorization decision.
 * This contract is NOT a durable grant and does not execute tools. A trusted
 * caller must load current policy and re-evaluate at the actual point of use.
 */
public final class GovernedToolResourcePolicy {
    private GovernedToolResourcePolicy() {}

    public record Request(String workflowId, String taskId, String attemptId,
                          String specialistId, String specialistVersion,
                          String tool, String action, String resource) {
        public Request {
            requireToken(workflowId, "workflowId");
            requireToken(taskId, "taskId");
            requireToken(attemptId, "attemptId");
            requireToken(specialistId, "specialistId");
            requireToken(specialistVersion, "specialistVersion");
            requireToken(tool, "tool");
            requireToken(action, "action");
            requireResource(resource);
        }
    }

    public record Grant(String tool, String action, String resource) {
        public Grant {
            requireToken(tool, "tool");
            requireToken(action, "action");
            requireResource(resource);
        }
    }

    /** Revision belongs to tool/resource policy, not the specialist registry. */
    public record Snapshot(String workflowId, String taskId, String attemptId,
                           String specialistId, String specialistVersion,
                           long policyRevision, Instant expiresAt, boolean revoked,
                           List<Grant> grants) {
        public Snapshot {
            requireToken(workflowId, "workflowId");
            requireToken(taskId, "taskId");
            requireToken(attemptId, "attemptId");
            requireToken(specialistId, "specialistId");
            requireToken(specialistVersion, "specialistVersion");
            if (policyRevision < 0) throw new IllegalArgumentException("Invalid policy revision");
            Objects.requireNonNull(expiresAt, "expiresAt");
            grants = List.copyOf(Objects.requireNonNull(grants, "grants"));
            if (grants.size() > 256 || grants.stream().anyMatch(Objects::isNull) ||
                Set.copyOf(grants).size() != grants.size())
                throw new IllegalArgumentException("Invalid or duplicate grants");
        }
    }

    public enum Denial { NONE, IDENTITY_MISMATCH, REVISION_MISMATCH, EXPIRED, REVOKED, NOT_GRANTED }
    public record Decision(boolean allowed, Denial denial) {}

    /** Never trust a revision or identity supplied by a model; caller supplies the expected values. */
    public static Decision evaluate(Request request, Snapshot current,
                                    long expectedRevision, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(now, "now");
        if (!request.workflowId().equals(current.workflowId()) ||
            !request.taskId().equals(current.taskId()) ||
            !request.attemptId().equals(current.attemptId()) ||
            !request.specialistId().equals(current.specialistId()) ||
            !request.specialistVersion().equals(current.specialistVersion()))
            return new Decision(false, Denial.IDENTITY_MISMATCH);
        if (expectedRevision < 0 || expectedRevision != current.policyRevision())
            return new Decision(false, Denial.REVISION_MISMATCH);
        if (current.revoked()) return new Decision(false, Denial.REVOKED);
        if (!now.isBefore(current.expiresAt())) return new Decision(false, Denial.EXPIRED);
        return current.grants().contains(new Grant(request.tool(), request.action(), request.resource()))
            ? new Decision(true, Denial.NONE) : new Decision(false, Denial.NOT_GRANTED);
    }

    private static void requireToken(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid " + field);
    }

    private static void requireResource(String resource) {
        if (resource == null || resource.isBlank() || resource.length() > 1024 ||
            resource.chars().anyMatch(c -> Character.isISOControl(c)) ||
            resource.indexOf('*') >= 0 || resource.indexOf('?') >= 0)
            throw new IllegalArgumentException("Resource must be an exact, bounded identifier");
    }
}
