package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GovernedToolResourcePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-16T20:00:00Z");
    private static final GovernedToolResourcePolicy.Grant READ =
        new GovernedToolResourcePolicy.Grant("storage", "read", "artifact://project/doc-1");

    private GovernedToolResourcePolicy.Request request(String resource) {
        return new GovernedToolResourcePolicy.Request("wf", "task", "attempt", "researcher", "1.0",
            "storage", "read", resource);
    }
    private GovernedToolResourcePolicy.Snapshot policy(long revision, Instant expiry, boolean revoked) {
        return new GovernedToolResourcePolicy.Snapshot("wf", "task", "attempt", "researcher", "1.0",
            revision, expiry, revoked, List.of(READ));
    }
    @Test void allowsOnlyExactGrant() {
        assertTrue(GovernedToolResourcePolicy.evaluate(request("artifact://project/doc-1"),
            policy(4, NOW.plusSeconds(30), false), 4, NOW).allowed());
        assertEquals(GovernedToolResourcePolicy.Denial.NOT_GRANTED,
            GovernedToolResourcePolicy.evaluate(request("artifact://project/doc-2"),
                policy(4, NOW.plusSeconds(30), false), 4, NOW).denial());
    }
    @Test void deniesStaleRevisionAndRevocation() {
        assertEquals(GovernedToolResourcePolicy.Denial.REVISION_MISMATCH,
            GovernedToolResourcePolicy.evaluate(request(READ.resource()),
                policy(5, NOW.plusSeconds(30), false), 4, NOW).denial());
        assertEquals(GovernedToolResourcePolicy.Denial.REVOKED,
            GovernedToolResourcePolicy.evaluate(request(READ.resource()),
                policy(4, NOW.plusSeconds(30), true), 4, NOW).denial());
    }
    @Test void deniesExpiryAtBoundaryAndDifferentIdentity() {
        assertEquals(GovernedToolResourcePolicy.Denial.EXPIRED,
            GovernedToolResourcePolicy.evaluate(request(READ.resource()), policy(4, NOW, false), 4, NOW).denial());
        var other = new GovernedToolResourcePolicy.Request("wf", "task", "another", "researcher", "1.0",
            "storage", "read", READ.resource());
        assertEquals(GovernedToolResourcePolicy.Denial.IDENTITY_MISMATCH,
            GovernedToolResourcePolicy.evaluate(other, policy(4, NOW.plusSeconds(30), false), 4, NOW).denial());
    }
    @Test void rejectsWildcardAndDuplicateGrants() {
        assertThrows(IllegalArgumentException.class,
            () -> new GovernedToolResourcePolicy.Grant("storage", "read", "artifact://project/*"));
        assertThrows(IllegalArgumentException.class,
            () -> new GovernedToolResourcePolicy.Snapshot("wf", "task", "attempt", "researcher", "1.0",
                1, NOW.plusSeconds(30), false, List.of(READ, READ)));
    }
    @Test void noImplicitGrant() {
        var empty = new GovernedToolResourcePolicy.Snapshot("wf", "task", "attempt", "researcher", "1.0",
            4, NOW.plusSeconds(30), false, List.of());
        assertEquals(GovernedToolResourcePolicy.Denial.NOT_GRANTED,
            GovernedToolResourcePolicy.evaluate(request(READ.resource()), empty, 4, NOW).denial());
    }
}
