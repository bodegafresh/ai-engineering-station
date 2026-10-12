package dev.aies.control.capabilities;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityPolicyTest {
    @Test void denyByDefaultAndNoImplicitWrites() {
        var p = new CapabilityPolicy(List.of(new CapabilityPolicy.Grant("sre-engineer", "grafana-read", CapabilityPolicy.Decision.ALLOW_READ),
                new CapabilityPolicy.Grant("sre-engineer", "telegram-send", CapabilityPolicy.Decision.REQUIRE_APPROVAL)));
        assertTrue(p.canExecute("sre-engineer", "grafana-read", false));
        assertFalse(p.canExecute("sre-engineer", "telegram-send", false));
        assertFalse(p.canExecute("unknown", "grafana-read", true));
        assertFalse(p.canExecute("sre-engineer", "restart-service", true));
    }
    @Test void gatewayEnforcesApprovalAndAudits() throws Exception {
        var p = new CapabilityPolicy(List.of(new CapabilityPolicy.Grant("incident-commander", "telegram-send", CapabilityPolicy.Decision.REQUIRE_APPROVAL)));
        AtomicInteger calls = new AtomicInteger();
        List<String> audit = new ArrayList<>();
        var gateway = new ToolGateway(p, (ref, s, c) -> ref.equals("approved-123"), (s,c,o) -> audit.add(o));
        assertThrows(SecurityException.class, () -> gateway.execute("incident-commander", "telegram-send", "send", "hello", null,
                (op,in) -> { calls.incrementAndGet(); return "sent"; }));
        assertEquals(0, calls.get());
        assertEquals("sent", gateway.execute("incident-commander", "telegram-send", "send", "hello", "approved-123",
                (op,in) -> { calls.incrementAndGet(); return "sent"; }));
        assertEquals(1, calls.get());
        assertEquals(List.of("DENIED", "STARTED", "SUCCEEDED"), audit);
    }
}
