package dev.aies.domain;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class TaskTest {
    @Test void rejectsEmptyObjective() {
        Instant now=Instant.now();
        assertThrows(IllegalArgumentException.class,()->new Task(UUID.randomUUID()," ",TaskStatus.PENDING,now,now));
    }
}
