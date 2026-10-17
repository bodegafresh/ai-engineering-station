package dev.aies.control.execution;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded durable admission contract, independent of filesystem publication. */
class GovernedPublicationAdmissionTest {
    @TempDir Path temp;
    private GovernedToolResourcePolicy.Request request() {
        return new GovernedToolResourcePolicy.Request("wf","task","attempt","engineer","1.0",
            "workspace-documentation","invoke-model","ollama:software-engineer");
    }
    private GovernedToolResourcePolicy.Snapshot policy() {
        return new GovernedToolResourcePolicy.Snapshot("wf","task","attempt","engineer","1.0",7,
            Instant.parse("2099-01-01T00:00:00Z"),false,List.of(
                new GovernedToolResourcePolicy.Grant("workspace-documentation","invoke-model","ollama:software-engineer")));
    }
    @Test void admissionIsDurableAndOrderedBeforeLaterRevocation() throws Exception {
        var path=temp.resolve("policies.sqlite");
        try(var a=new GovernedToolResourcePolicyStore(path);var b=new GovernedToolResourcePolicyStore(path)) {
            a.approve(policy());a.admitPublication(request());
            assertTrue(b.publicationAdmitted("wf","task","attempt"));
            b.revoke("wf","task","attempt");
            assertTrue(a.publicationAdmitted("wf","task","attempt"));
            assertThrows(SecurityException.class,()->a.admitPublication(request()));
        }
        try(var restarted=new GovernedToolResourcePolicyStore(path)) {
            assertTrue(restarted.publicationAdmitted("wf","task","attempt"));
        }
    }
    @Test void revocationBeforeAdmissionDenies() throws Exception {
        try(var store=new GovernedToolResourcePolicyStore(temp.resolve("policies.sqlite"))) {
            store.approve(policy());store.revoke("wf","task","attempt");
            assertThrows(SecurityException.class,()->store.admitPublication(request()));
            assertFalse(store.publicationAdmitted("wf","task","attempt"));
        }
    }
    @Test void missingOrWrongGrantCannotAdmit() throws Exception {
        try(var store=new GovernedToolResourcePolicyStore(temp.resolve("policies.sqlite"))) {
            assertThrows(SecurityException.class,()->store.admitPublication(request()));
            assertFalse(store.publicationAdmitted("wf","task","attempt"));
        }
    }
}
