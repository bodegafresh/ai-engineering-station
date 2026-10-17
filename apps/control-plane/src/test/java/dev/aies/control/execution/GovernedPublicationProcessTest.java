package dev.aies.control.execution;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-JVM admission/revocation ordering. This does not exercise document recovery,
 * transaction fault injection, or OS power-loss persistence.
 */
class GovernedPublicationProcessTest {
    @TempDir Path temp;

    private Path setup() throws Exception {
        Path db = temp.resolve("policy.sqlite");
        try(var store = new GovernedToolResourcePolicyStore(db)) {
            store.approve(new GovernedToolResourcePolicy.Snapshot(
                "wf","task","attempt","engineer","1.0",7,
                Instant.parse("2099-01-01T00:00:00Z"),false,List.of(
                new GovernedToolResourcePolicy.Grant("workspace-documentation",
                    "invoke-model","ollama:software-engineer"))));
        }
        return db;
    }

    private static String classpath() throws Exception {
        var entries = new LinkedHashSet<String>();
        entries.addAll(Arrays.asList(System.getProperty("java.class.path","").split(
            java.util.regex.Pattern.quote(File.pathSeparator))));
        for(ClassLoader loader=GovernedPublicationProcessTest.class.getClassLoader();
            loader!=null;loader=loader.getParent()) {
            if(loader instanceof java.net.URLClassLoader urls) {
                for(var url:urls.getURLs()) {
                    if("file".equals(url.getProtocol())) entries.add(Path.of(url.toURI()).toString());
                }
            }
        }
        return String.join(File.pathSeparator,entries);
    }

    private static Process start(Path db,String action) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),
            "-cp",classpath(),GovernedPublicationProcessChild.class.getName(),
            db.toString(),action).redirectErrorStream(true).start();
    }
    private static int waitFor(Process p) throws Exception {
        try {
            assertTrue(p.waitFor(30,TimeUnit.SECONDS),"Child JVM timed out");
            int exit=p.exitValue();
            String output=new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(exit==0 || exit==21,"Child JVM failed ("+exit+"): "+output);
            return exit;
        } finally {
            if(p.isAlive()) p.destroyForcibly().waitFor();
        }
    }

    @Test void revokeBeforeAdmissionAcrossProcessesDenies() throws Exception {
        Path db=setup();
        assertEquals(0,waitFor(start(db,"revoke")));
        assertEquals(21,waitFor(start(db,"admit")));
        try(var reopened=new GovernedToolResourcePolicyStore(db)) {
            assertFalse(reopened.publicationAdmitted("wf","task","attempt"));
        }
    }

    @Test void admissionBeforeRevocationAcrossProcessesRemainsDurable() throws Exception {
        Path db=setup();
        assertEquals(0,waitFor(start(db,"admit")));
        assertEquals(0,waitFor(start(db,"revoke")));
        assertEquals(21,waitFor(start(db,"admit")));
        try(var reopened=new GovernedToolResourcePolicyStore(db)) {
            assertTrue(reopened.publicationAdmitted("wf","task","attempt"));
        }
    }

    @Test void simultaneousIndependentProcessesHaveConsistentOutcome() throws Exception {
        Path db=setup();
        Process admit=start(db,"admit");
        Process revoke=start(db,"revoke");
        int admitted=waitFor(admit);
        assertEquals(0,waitFor(revoke));
        try(var reopened=new GovernedToolResourcePolicyStore(db)) {
            assertEquals(admitted==0,reopened.publicationAdmitted("wf","task","attempt"));
        }
    }
}
