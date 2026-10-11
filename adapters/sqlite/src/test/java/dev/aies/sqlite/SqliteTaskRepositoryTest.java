package dev.aies.sqlite;
import dev.aies.domain.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class SqliteTaskRepositoryTest {
    @TempDir Path temp;
    @Test void survivesRestartAndPublishesOutbox() {
        String db=temp.resolve("tasks.db").toString();
        var first=new SqliteTaskRepository(db);
        var task=first.create("Persist and recover");
        assertEquals(TaskStatus.PENDING,first.find(task.id()).orElseThrow().status());
        assertEquals(1,first.pendingEvents(10).size());
        var second=new SqliteTaskRepository(db);
        assertEquals(TaskStatus.RUNNING,second.claimNext(30).orElseThrow().status());
        assertTrue(second.complete(task.id()));
        assertEquals(TaskStatus.COMPLETED,second.find(task.id()).orElseThrow().status());
        assertFalse(second.complete(task.id()));
        for(var event:second.pendingEvents(10))second.acknowledgeEvent(event.id());
        assertTrue(second.pendingEvents(10).isEmpty());
    }
    @Test void cancelPreventsClaim() {
        var repository=new SqliteTaskRepository(temp.resolve("cancel.db").toString());
        var task=repository.create("Cancel me");
        assertTrue(repository.cancel(task.id()));
        assertEquals(TaskStatus.CANCELLED,repository.find(task.id()).orElseThrow().status());
        assertTrue(repository.claimNext(30).isEmpty());
    }
    @Test void failedAttemptCanRetry() {
        var repository=new SqliteTaskRepository(temp.resolve("retry.db").toString());
        var task=repository.create("Retry me");
        assertTrue(repository.claimNext(30).isPresent());
        assertTrue(repository.failOrRetry(task.id(),"temporary",2,0));
        assertEquals(TaskStatus.PENDING,repository.find(task.id()).orElseThrow().status());
        assertTrue(repository.claimNext(30).isPresent());
        assertTrue(repository.failOrRetry(task.id(),"permanent",2,0));
        assertEquals(TaskStatus.FAILED,repository.find(task.id()).orElseThrow().status());
    }
}
