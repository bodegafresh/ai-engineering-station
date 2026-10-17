package dev.aies.control.execution;

import java.nio.file.Path;

/** Test-only independent JVM worker. Exit 0 = admitted/revoked, 21 = admission denied. */
public final class GovernedPublicationProcessChild {
    private GovernedPublicationProcessChild() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("policy database, action");
        try (var store = new GovernedToolResourcePolicyStore(Path.of(args[0]))) {
            switch (args[1]) {
                case "admit" -> {
                    try {
                        store.admitPublication(new GovernedToolResourcePolicy.Request(
                            "wf","task","attempt","engineer","1.0",
                            "workspace-documentation","invoke-model","ollama:software-engineer"));
                    } catch (SecurityException denied) { System.exit(21); }
                }
                case "revoke" -> store.revoke("wf","task","attempt");
                default -> throw new IllegalArgumentException("Unknown action");
            }
        }
    }
}
