package dev.aies.control.execution;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GovernedPolicyOperatorResourceTest {
    @Test void invalidGrantFailsBeforeApproval() {
        assertThrows(IllegalArgumentException.class,
            () -> new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:*"));
    }
    @Test void snapshotIsImmutableAndRejectsDuplicates() {
        var g = new GovernedToolResourcePolicy.Grant("ollama","invoke-model","ollama:software-engineer");
        assertThrows(IllegalArgumentException.class, () -> new GovernedToolResourcePolicy.Snapshot(
            "wf","task","attempt","software-engineer","1.0",1,
            Instant.now().plusSeconds(60),false,List.of(g,g)));
    }
}
