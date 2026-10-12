package dev.aies.control.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/** Single fail-closed operator credential policy for workspace operations. */
@ApplicationScoped
public class WorkspaceOperatorAuthorizer {
    @ConfigProperty(name = "aies.workspace-changes.token")
    Optional<String> token;

    public void authorize(boolean featureEnabled, String supplied) {
        if (!accepted(featureEnabled, token.orElse(null), supplied)) {
            throw new NotFoundException();
        }
    }

    /** Package-visible policy boundary for isolated tests. */
    static boolean accepted(boolean enabled, String expected, String supplied) {
        if (!enabled || expected == null || expected.length() < 32 || supplied == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
