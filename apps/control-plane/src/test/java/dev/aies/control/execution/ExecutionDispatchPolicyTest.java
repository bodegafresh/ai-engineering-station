package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionDispatchPolicyTest {
    @Test void validatesTokens() {
        assertEquals("valid-token",ExecutionDispatchPolicy.token("valid-token"));
        assertThrows(IllegalArgumentException.class,()->ExecutionDispatchPolicy.token(""));
        assertThrows(IllegalArgumentException.class,()->ExecutionDispatchPolicy.token(null));
        assertThrows(IllegalArgumentException.class,()->ExecutionDispatchPolicy.token("x".repeat(129)));
    }
}
