package dev.aies.control.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Dedicated child-JVM entrypoint: no in-memory store is shared with the parent. */
public final class GovernedPolicyMultiJvmChild {
    private static final String WF = "wf-execution", TASK = "a";
    private static final GovernedToolResourcePolicy.Grant GRANT =
        new GovernedToolResourcePolicy.Grant("ollama", "invoke-model", "ollama:software-engineer");

    private static ExecutionHandoffStore.GovernedAttempt attempt(String token) {
        return new ExecutionHandoffStore.GovernedAttempt(WF,TASK,token,
            "software-engineer","1.0","software","a".repeat(64),7);
    }
    private static GovernedToolResourcePolicy.Request request(String token) {
        return new GovernedToolResourcePolicy.Request(WF,TASK,token,
            "software-engineer","1.0",GRANT.tool(),GRANT.action(),GRANT.resource());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("action handoff policy token coordination");
        String action=args[0], token=args[3];
        Path handoffPath=Path.of(args[1]), policyPath=Path.of(args[2]), coordination=Path.of(args[4]);
        switch (action) {
            case "authorize" -> {
                try (var handoff=new ExecutionHandoffStore(handoffPath,new ObjectMapper());
                     var policies=new GovernedToolResourcePolicyStore(policyPath)) {
                    policies.requireAuthorizedCurrent(handoff,attempt(token),request(token));
                }
            }
            case "deny" -> {
                try (var handoff=new ExecutionHandoffStore(handoffPath,new ObjectMapper());
                     var policies=new GovernedToolResourcePolicyStore(policyPath)) {
                    try {
                        policies.requireAuthorizedCurrent(handoff,attempt(token),request(token));
                        throw new AssertionError("Authorization unexpectedly allowed");
                    } catch (SecurityException expected) { /* expected fail-closed */ }
                }
            }
            case "revoke" -> {
                try (var policies=new GovernedToolResourcePolicyStore(policyPath)) {
                    policies.revoke(WF,TASK,token);
                }
            }
            case "compete42", "compete43" -> {
                long revision=action.equals("compete42") ? 42 : 43;
                // Both child JVMs open independent SQLite connections before the race.
                try (var policies=new GovernedToolResourcePolicyStore(policyPath)) {
                    Files.writeString(coordination.resolve(action+".ready"),"ready");
                    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                    while (!Files.exists(coordination.resolve("go"))) {
                        if (System.nanoTime()>deadline) throw new IllegalStateException("Barrier timed out");
                        Thread.sleep(10);
                    }
                    var snapshot=new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
                        "software-engineer","1.0",revision,Instant.parse("2099-01-01T00:00:00Z"),
                        false,List.of(GRANT));
                    try { policies.approve(snapshot); System.out.println("APPROVED"); }
                    catch (SecurityException denied) { System.out.println("DENIED"); }
                }
            }
            case "approveThenWaitForKill" -> {
                // Persist approval first, then signal the parent. The child never binds
                // policy_revision: this reproduces the real two-store crash window.
                try (var policies=new GovernedToolResourcePolicyStore(policyPath)) {
                    policies.approve(new GovernedToolResourcePolicy.Snapshot(WF,TASK,token,
                        "software-engineer","1.0",42,Instant.parse("2099-01-01T00:00:00Z"),
                        false,List.of(GRANT)));
                }
                Files.writeString(coordination.resolve("approved.ready"),"durable");
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while(System.nanoTime()<deadline) Thread.sleep(50);
                throw new IllegalStateException("Expected parent to terminate this child");
            }
            default -> throw new IllegalArgumentException("Unknown action: "+action);
        }
    }
}
