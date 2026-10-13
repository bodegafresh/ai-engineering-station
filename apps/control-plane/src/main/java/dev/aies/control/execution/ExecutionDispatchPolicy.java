package dev.aies.control.execution;

import dev.aies.control.communication.CommunicationContracts;

/** Fail-closed validation for internal execution commands. */
public final class ExecutionDispatchPolicy {
    private ExecutionDispatchPolicy() {}
    public static String id(String id) {
        CommunicationContracts.id(id);
        return id;
    }
    public static String token(String token) {
        if (token == null || token.isBlank() || token.length() > 128)
            throw new IllegalArgumentException("Invalid lease token");
        return token;
    }
}
