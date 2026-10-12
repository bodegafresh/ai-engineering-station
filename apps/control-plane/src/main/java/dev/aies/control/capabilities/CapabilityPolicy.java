package dev.aies.control.capabilities;

import java.util.*;
import java.util.regex.Pattern;

/** Deny-by-default capabilities; never grants access from prompt text. */
public final class CapabilityPolicy {
    public enum Decision { ALLOW_READ, REQUIRE_APPROVAL, DENY }
    public record Grant(String specialist, String capability, Decision decision) {
        public Grant {
            Objects.requireNonNull(decision);
            if (!valid(specialist) || !valid(capability)) throw new IllegalArgumentException("Invalid grant");
        }
    }
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    private final Map<String, Decision> grants;
    private static boolean valid(String id) { return id != null && ID.matcher(id).matches(); }
    private static String key(String specialist, String capability) { return specialist + ":" + capability; }

    public CapabilityPolicy(Collection<Grant> configured) {
        Objects.requireNonNull(configured);
        Map<String, Decision> result = new HashMap<>();
        for (Grant grant : configured) {
            if (result.putIfAbsent(key(grant.specialist(), grant.capability()), grant.decision()) != null)
                throw new IllegalArgumentException("Duplicate grant");
        }
        grants = Map.copyOf(result);
    }
    public Decision decide(String specialist, String capability) {
        if (!valid(specialist) || !valid(capability)) return Decision.DENY;
        return grants.getOrDefault(key(specialist, capability), Decision.DENY);
    }
    /** A valid approval token must be verified by an external trusted approval service. */
    public boolean canExecute(String specialist, String capability, boolean trustedApprovalVerified) {
        return switch (decide(specialist, capability)) {
            case ALLOW_READ -> capability.endsWith("-read");
            case REQUIRE_APPROVAL -> trustedApprovalVerified;
            case DENY -> false;
        };
    }
}
