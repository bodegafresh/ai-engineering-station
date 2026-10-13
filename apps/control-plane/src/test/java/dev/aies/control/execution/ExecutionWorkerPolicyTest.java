package dev.aies.control.execution;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionWorkerPolicyTest {
    @Test void rejectsUnexecutedOrEmptyResults() {
        assertThrows(IllegalStateException.class,()->ExecutionWorkerPolicy.content(new JsonObject().put("executed",false).put("content","fake")));
        assertThrows(IllegalArgumentException.class,()->ExecutionWorkerPolicy.content(new JsonObject().put("executed",true).put("content"," ")));
        assertEquals("real output",ExecutionWorkerPolicy.content(new JsonObject().put("executed",true).put("content","real output")));
    }
    @Test void rejectsInvalidWorkerTimeout() {
        assertThrows(IllegalArgumentException.class,()->new ExecutionWorkerEngine(null,null,null,3,60,60));
    }
}
