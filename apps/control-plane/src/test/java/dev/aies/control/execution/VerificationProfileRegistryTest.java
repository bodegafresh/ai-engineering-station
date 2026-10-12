package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationProfileRegistryTest {
    @Test void containsEightApprovedProfiles() {
        var registry=new VerificationProfileRegistry();
        assertEquals(8,registry.ids().size());
        for(var id:registry.ids()) {
            var p=registry.require(id);
            assertEquals(id,p.id());
            assertTrue(p.timeoutSeconds()<=300);
            assertTrue(p.configKey().startsWith("aies.workspace-verification.images."));
        }
    }
    @Test void rejectsUnknownProfile() {
        assertThrows(IllegalArgumentException.class,()->new VerificationProfileRegistry().require("custom-shell"));
        assertThrows(IllegalArgumentException.class,()->new VerificationProfileRegistry().require(null));
    }
    @Test void requiresPinnedImage() {
        assertThrows(IllegalArgumentException.class,()->new DockerVerificationRunner("python:3.13",180));
    }
}
