package dev.aies.control.execution;

import java.nio.file.Path;

/** Dedicated subprocess: protocol is stdout READY -> parent sends RELEASE -> stdout DONE. */
public final class GovernedPublicationGateProcessChild {
    private GovernedPublicationGateProcessChild() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected policy DB path");
        try (var gate = GovernedPublicationGate.acquire(Path.of(args[0]))) {
            System.out.println("READY");
            System.out.flush();
            String command = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
            if (!"RELEASE".equals(command)) throw new SecurityException("Unexpected child command");
        }
        System.out.println("DONE");
        System.out.flush();
    }
}
