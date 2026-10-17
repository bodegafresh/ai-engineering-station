package dev.aies.control.execution;

import java.nio.file.Path;

/** Test-only independent JVM worker. Exit 0 = admitted/revoked, 21 = admission denied. */
public final class GovernedPublicationProcessChild {
    private GovernedPublicationProcessChild() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2 && args.length != 4) throw new IllegalArgumentException("policy database, action [, ready, release]");
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
                case "admit-after-barrier" -> {
                    if (args.length != 4) throw new IllegalArgumentException("db, action, ready, release");
                    java.nio.file.Path ready=Path.of(args[2]);
                    java.nio.file.Path release=Path.of(args[3]);
                    // An independent JVM has opened the database before the operator acts.
                    java.nio.file.Files.writeString(ready,"ready");
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while (!java.nio.file.Files.exists(release)) {
                        if (System.nanoTime()>deadline) throw new IllegalStateException("Barrier timed out");
                        Thread.sleep(10);
                    }
                    try {
                        store.admitPublication(new GovernedToolResourcePolicy.Request(
                            "wf","task","attempt","engineer","1.0",
                            "workspace-documentation","invoke-model","ollama:software-engineer"));
                    } catch (SecurityException denied) { System.exit(21); }
                }
                case "admit-then-pause" -> {
                    if (args.length != 4) throw new IllegalArgumentException("db, action, ready, release");
                    store.admitPublication(new GovernedToolResourcePolicy.Request(
                        "wf","task","attempt","engineer","1.0",
                        "workspace-documentation","invoke-model","ollama:software-engineer"));
                    // The child has durably admitted but has not performed a filesystem write.
                    java.nio.file.Files.writeString(Path.of(args[2]),"admitted");
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    while (!java.nio.file.Files.exists(Path.of(args[3]))) {
                        if (System.nanoTime()>deadline) throw new IllegalStateException("Post-admission barrier timed out");
                        Thread.sleep(10);
                    }
                    // A historical admission remains durable after revocation. It is NOT a
                    // fencing token and cannot authorize a new filesystem write.
                    if (!store.publicationAdmitted("wf","task","attempt"))
                        throw new IllegalStateException("Committed admission disappeared");
                    try {
                        store.admitPublication(new GovernedToolResourcePolicy.Request(
                            "wf","task","attempt","engineer","1.0",
                            "workspace-documentation","invoke-model","ollama:software-engineer"));
                        throw new IllegalStateException("Duplicate admission unexpectedly succeeded");
                    } catch (SecurityException expected) { /* durable duplicate/revocation denial */ }
                }
                default -> throw new IllegalArgumentException("Unknown action");
            }
        }
    }
}
