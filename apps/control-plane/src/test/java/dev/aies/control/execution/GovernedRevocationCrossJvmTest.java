package dev.aies.control.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A real second JVM holds the publication gate while production revoke is attempted. */
class GovernedRevocationCrossJvmTest {
    private static final String WF = "wf-fence", TASK = "task", ATTEMPT = "attempt";
    private static final GovernedToolResourcePolicy.Request REQUEST =
        new GovernedToolResourcePolicy.Request(WF, TASK, ATTEMPT, "software-engineer", "1.0.0",
            "workspace-documentation", "invoke-model", "ollama:software-engineer");

    private static Process launch(Path db) throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String tests = Path.of(GovernedPublicationGateProcessChild.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        String main = Path.of(GovernedPublicationGate.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(executable, "-cp", tests + java.io.File.pathSeparator + main,
            GovernedPublicationGateProcessChild.class.getName(), db.toString())
            .redirectErrorStream(true).start();
    }

    @Test @Timeout(45)
    void productionRevocationWaitsForIndependentJvmPublicationGate() throws Exception {
        Path db = Files.createTempDirectory("aies-revoke-jvm-").resolve("policies.sqlite");
        try (var policy = new GovernedToolResourcePolicyStore(db)) {
            policy.approve(new GovernedToolResourcePolicy.Snapshot(WF, TASK, ATTEMPT,
                "software-engineer", "1.0.0", 7, Instant.parse("2099-01-01T00:00:00Z"),
                false, List.of(new GovernedToolResourcePolicy.Grant(REQUEST.tool(), REQUEST.action(), REQUEST.resource()))));
        }
        Process holder = launch(db);
        try {
            var out = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("READY", CompletableFuture.supplyAsync(() -> {
                try { return out.readLine(); } catch (Exception e) { throw new RuntimeException(e); }
            }).get(15, TimeUnit.SECONDS));
            CompletableFuture<Void> revoke = CompletableFuture.runAsync(() -> {
                try (var policy = new GovernedToolResourcePolicyStore(db)) {
                    policy.revoke(WF, TASK, ATTEMPT);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertThrows(TimeoutException.class, () -> revoke.get(300, TimeUnit.MILLISECONDS),
                "Revocation acknowledged while another JVM still owns publication gate");
            try (var policy = new GovernedToolResourcePolicyStore(db)) {
                assertTrue(policy.evaluate(REQUEST, 7, Instant.now()).allowed(),
                    "Policy must remain active until holder releases gate");
            }
            var writer = new OutputStreamWriter(holder.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("RELEASE\n"); writer.flush();
            assertEquals("DONE", CompletableFuture.supplyAsync(() -> {
                try { return out.readLine(); } catch (Exception e) { throw new RuntimeException(e); }
            }).get(15, TimeUnit.SECONDS));
            assertTrue(holder.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, holder.exitValue());
            revoke.get(15, TimeUnit.SECONDS);
            try (var policy = new GovernedToolResourcePolicyStore(db)) {
                var decision = policy.evaluate(REQUEST, 7, Instant.now());
                assertFalse(decision.allowed());
                assertEquals(GovernedToolResourcePolicy.Denial.REVOKED, decision.denial());
            }
        } finally {
            holder.destroyForcibly(); holder.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
