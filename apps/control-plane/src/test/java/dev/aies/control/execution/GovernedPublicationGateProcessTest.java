package dev.aies.control.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real independent JVMs, deterministic process barriers and OS lock recovery. */
class GovernedPublicationGateProcessTest {
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    private static Process launch(Path database) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testClasses = Path.of(GovernedPublicationGateProcessChild.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        String mainClasses = Path.of(GovernedPublicationGate.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        return new ProcessBuilder(javaExecutable, "-cp", testClasses + java.io.File.pathSeparator + mainClasses,
            GovernedPublicationGateProcessChild.class.getName(), database.toString())
            .redirectErrorStream(true).start();
    }

    private static CompletableFuture<String> nextLine(BufferedReader reader) {
        return CompletableFuture.supplyAsync(() -> {
            try { return reader.readLine(); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
    }

    private static void release(Process process) throws Exception {
        var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        writer.write("RELEASE\n"); writer.flush();
    }

    @Test @Timeout(40)
    void independentJvmWaitsUntilOwnerReleases() throws Exception {
        Path db = Files.createTempDirectory("aies-cross-jvm-").resolve("policies.sqlite");
        Process first = launch(db);
        Process second = null;
        try {
            var firstOut = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("READY", nextLine(firstOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            second = launch(db);
            var secondOut = new BufferedReader(new InputStreamReader(second.getInputStream(), StandardCharsets.UTF_8));
            var secondReady = nextLine(secondOut);
            assertThrows(java.util.concurrent.TimeoutException.class,
                () -> secondReady.get(250, TimeUnit.MILLISECONDS), "Second JVM entered while owner held OS lock");
            release(first);
            assertEquals("DONE", nextLine(firstOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(first.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, first.exitValue());
            assertEquals("READY", secondReady.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            release(second);
            assertEquals("DONE", nextLine(secondOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(second.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, second.exitValue());
        } finally {
            first.destroyForcibly(); first.waitFor(5, TimeUnit.SECONDS);
            if (second != null) { second.destroyForcibly(); second.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test @Timeout(40)
    void abruptJvmDeathReleasesOsLock() throws Exception {
        Path db = Files.createTempDirectory("aies-cross-jvm-crash-").resolve("policies.sqlite");
        Process owner = launch(db);
        Process recovery = null;
        try {
            var ownerOut = new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("READY", nextLine(ownerOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            owner.destroyForcibly();
            assertTrue(owner.waitFor(15, TimeUnit.SECONDS), "Killed owner must terminate");
            recovery = launch(db);
            var recoveryOut = new BufferedReader(new InputStreamReader(recovery.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("READY", nextLine(recoveryOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            release(recovery);
            assertEquals("DONE", nextLine(recoveryOut).get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(recovery.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, recovery.exitValue());
        } finally {
            owner.destroyForcibly(); owner.waitFor(5, TimeUnit.SECONDS);
            if (recovery != null) { recovery.destroyForcibly(); recovery.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
