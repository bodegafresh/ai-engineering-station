package dev.aies.control.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperatorAuthorizerTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    @Test void acceptsOnlyConfiguredCredentialWhenEnabled() {
        assertTrue(WorkspaceOperatorAuthorizer.accepted(true, TOKEN, TOKEN));
        assertFalse(WorkspaceOperatorAuthorizer.accepted(false, TOKEN, TOKEN));
        assertFalse(WorkspaceOperatorAuthorizer.accepted(true, TOKEN, "incorrect"));
        assertFalse(WorkspaceOperatorAuthorizer.accepted(true, TOKEN, null));
        assertFalse(WorkspaceOperatorAuthorizer.accepted(true, "short", "short"));
        assertFalse(WorkspaceOperatorAuthorizer.accepted(true, null, TOKEN));
    }
}
