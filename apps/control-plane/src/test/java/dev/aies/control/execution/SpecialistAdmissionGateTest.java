package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpecialistAdmissionGateTest {
    @Test void saturationIsImmediateAndReleaseIsIdempotent() {
        var gate = new SpecialistAdmissionGate(1);
        var first = gate.tryAcquire();
        assertNotNull(first);
        assertNull(gate.tryAcquire());
        assertEquals(0, gate.availablePermits());
        first.close();
        first.close();
        assertEquals(1, gate.availablePermits());
        try (var second = gate.tryAcquire()) { assertNotNull(second); }
        assertEquals(1, gate.availablePermits());
    }
    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpecialistAdmissionGate(0));
        assertThrows(IllegalArgumentException.class, () -> new SpecialistAdmissionGate(129));
    }
}
