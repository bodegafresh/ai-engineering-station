package dev.aies.sqlite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class SqliteTaskRepositoryTest {
    @TempDir Path temp;
    @Test void pendingTasksSurviveReopeningAndAreReconciled() {
        String db=temp.resolve("tasks.db").toString();
        var first=new SqliteTaskRepository(db);
        var task=first.create("Persist and recover");
        var second=new SqliteTaskRepository(db);
        assertEquals(1,second.processPending(10));
        assertEquals("COMPLETED",second.find(task.id()).orElseThrow().status().name());
        assertEquals(0,second.processPending(10));
    }
}
