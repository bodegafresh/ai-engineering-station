package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionRecoveryReportTest {
    @Test void ambiguousInvocationAlwaysRequiresHumanReconciliation() {
        var attempt = new ExecutionRecoveryReport.Attempt("t", "a", "PREPARED",
            ExecutionRecoveryReport.diagnose("PREPARED", true), true);
        assertEquals("AMBIGUOUS_INVOCATION", attempt.diagnosis());
        assertEquals("MANUAL_RECONCILIATION_REQUIRED",
            ExecutionRecoveryReport.action("PENDING", false, List.of(attempt)));
    }
    @Test void writtenMaterialNeverAutomaticallyRetries() {
        assertEquals("FILE_RECOVERY_REQUIRED", ExecutionRecoveryReport.diagnose("FILE_WRITTEN", true));
        assertEquals("PREPARED_NOT_INVOKED", ExecutionRecoveryReport.diagnose("PREPARED", false));
        assertEquals("RECORDED", ExecutionRecoveryReport.diagnose("ARTIFACT_RECORDED", true));
    }
    @Test void expiredLeaseRequiresInspectionNotBlindRetry() {
        assertEquals("INSPECT_EXPIRED_LEASE", ExecutionRecoveryReport.action("LEASED", true, List.of()));
        assertEquals("WAIT_FOR_LEASE", ExecutionRecoveryReport.action("LEASED", false, List.of()));
        assertEquals("REVIEW_EVIDENCE", ExecutionRecoveryReport.action("EVIDENCE_PENDING", false, List.of()));
    }
}
