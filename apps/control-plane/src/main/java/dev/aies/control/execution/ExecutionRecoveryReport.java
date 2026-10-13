package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.communication.CommunicationContracts;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read-only durable recovery diagnosis; never changes leases or retries external effects. */
public final class ExecutionRecoveryReport {
    public record Attempt(String taskId, String attemptId, String phase, String diagnosis,
                          boolean modelInvocationReserved) {}
    public record Task(String taskId, String status, int attempts, boolean leaseExpired,
                       String action, List<Attempt> attemptHistory) {}
    public record Report(String workflowId, String inspectedAt, List<Task> tasks,
                         List<String> limitations) {}

    private final Path root;
    private final ObjectMapper mapper;
    public ExecutionRecoveryReport(Path root, ObjectMapper mapper) {
        this.root = Objects.requireNonNull(root); this.mapper = Objects.requireNonNull(mapper);
    }
    public Report inspect(String workflowId) throws Exception {
        CommunicationContracts.id(workflowId);
        var now = Instant.now();
        var tasks = new ArrayList<Task>();
        // Open the existing database via the established store; never create a workflow implicitly.
        try (var store = new ExecutionHandoffStore(root.resolve("execution-handoff.sqlite"), mapper)) {
            store.plan(workflowId);
            var file = root.resolve("execution-handoff.sqlite").toAbsolutePath().normalize();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + file)) {
                for (var work : store.list(workflowId)) {
                    var history = new ArrayList<Attempt>();
                    try (var ps = db.prepareStatement("SELECT a.attempt_id,a.phase, EXISTS(SELECT 1 FROM execution_model_invocations m WHERE m.workflow_id=a.workflow_id AND m.task_id=a.task_id AND m.attempt_id=a.attempt_id) AS invoked FROM execution_attempt_effects a WHERE a.workflow_id=? AND a.task_id=? ORDER BY a.updated_at,a.attempt_id")) {
                        ps.setString(1, workflowId); ps.setString(2, work.taskId());
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) {
                                String phase = rs.getString(2);
                                boolean invoked = rs.getInt(3) != 0;
                                history.add(new Attempt(work.taskId(), rs.getString(1), phase,
                                    diagnose(phase, invoked), invoked));
                            }
                        }
                    }
                    // Invocation reservations without a prepared effect are also ambiguous.
                    try (var ps = db.prepareStatement("SELECT m.attempt_id FROM execution_model_invocations m WHERE m.workflow_id=? AND m.task_id=? AND NOT EXISTS(SELECT 1 FROM execution_attempt_effects a WHERE a.workflow_id=m.workflow_id AND a.task_id=m.task_id AND a.attempt_id=m.attempt_id) ORDER BY m.started_at,m.attempt_id")) {
                        ps.setString(1, workflowId); ps.setString(2, work.taskId());
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) history.add(new Attempt(work.taskId(), rs.getString(1),
                                "MISSING_EFFECT", "AMBIGUOUS_INVOCATION", true));
                        }
                    }
                    boolean expired = "LEASED".equals(work.status()) && work.leaseUntil() != null
                        && !Instant.parse(work.leaseUntil()).isAfter(now);
                    tasks.add(new Task(work.taskId(), work.status(), work.attempts(), expired,
                        action(work.status(), expired, history), List.copyOf(history)));
                }
            }
        }
        return new Report(workflowId, now.toString(), List.copyOf(tasks), List.of(
            "Point-in-time read across SQLite connections; not an atomic transaction",
            "A model timeout does not cancel an external inference",
            "No retry, lease reset, approval, or material mutation is performed",
            "Attempt IDs are sensitive correlation identifiers; do not publish this report"));
    }
    static String diagnose(String phase, boolean invoked) {
        return switch (phase) {
            case "ARTIFACT_RECORDED" -> "RECORDED";
            case "FILE_WRITTEN" -> "FILE_RECOVERY_REQUIRED";
            case "PREPARED" -> invoked ? "AMBIGUOUS_INVOCATION" : "PREPARED_NOT_INVOKED";
            default -> "MANUAL_RECONCILIATION_REQUIRED";
        };
    }
    static String action(String status, boolean expired, List<Attempt> history) {
        if (history.stream().anyMatch(a -> a.diagnosis().equals("AMBIGUOUS_INVOCATION")
                || a.diagnosis().equals("FILE_RECOVERY_REQUIRED")
                || a.diagnosis().equals("MANUAL_RECONCILIATION_REQUIRED")))
            return "MANUAL_RECONCILIATION_REQUIRED";
        return switch (status) {
            case "COMPLETED" -> "NONE";
            case "EVIDENCE_PENDING" -> "REVIEW_EVIDENCE";
            case "LEASED" -> expired ? "INSPECT_EXPIRED_LEASE" : "WAIT_FOR_LEASE";
            case "DEAD", "RECONCILIATION_REQUIRED" -> "MANUAL_RECONCILIATION_REQUIRED";
            case "PENDING", "RETRY_PENDING" -> "CHECK_DEPENDENCIES_BEFORE_DISPATCH";
            default -> "MANUAL_RECONCILIATION_REQUIRED";
        };
    }
}
