package dev.aies.sqlite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
class SqliteModelResultTest {
    @TempDir Path temp;
    @Test void storesModelOutputAtomicallyAndSurvivesRestart() {
        String db=temp.resolve("model.db").toString();
        var repo=new SqliteTaskRepository(db);
        var task=repo.create("Explain hexagonal architecture");
        var claim=repo.claimNext(Duration.ofMinutes(2),3).orElseThrow();
        assertTrue(repo.completeWithResult(claim,"Ports and adapters...","mock-local"));
        assertFalse(repo.completeWithResult(claim,"overwritten","mock-local"));
        var result=new SqliteTaskRepository(db).findResult(task.id()).orElseThrow();
        assertEquals("Ports and adapters...",result.response());
        assertEquals("mock-local",result.model());
    }
    @Test void emptyOutputCannotBeMarkedCompleted() {
        var repo=new SqliteTaskRepository(temp.resolve("empty.db").toString());
        var task=repo.create("Test");
        var claim=repo.claimNext(Duration.ofMinutes(2),3).orElseThrow();
        assertThrows(IllegalArgumentException.class,()->repo.completeWithResult(claim,"","local"));
        assertEquals("RUNNING",repo.find(task.id()).orElseThrow().status().name());
    }
}
