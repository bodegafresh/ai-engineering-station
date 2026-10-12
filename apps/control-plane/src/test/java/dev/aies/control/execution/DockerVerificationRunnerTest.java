package dev.aies.control.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DockerVerificationRunnerTest {
    @Test void requiresPinnedImageAndBoundedTimeout() {
        assertThrows(IllegalArgumentException.class,()->new DockerVerificationRunner("eclipse-temurin:21",30));
        assertThrows(IllegalArgumentException.class,()->new DockerVerificationRunner(
                "local/aies-verify@sha256:"+"a".repeat(64),999));
        assertDoesNotThrow(()->new DockerVerificationRunner(
                "local/aies-verify@sha256:"+"a".repeat(64),180));
    }
}
