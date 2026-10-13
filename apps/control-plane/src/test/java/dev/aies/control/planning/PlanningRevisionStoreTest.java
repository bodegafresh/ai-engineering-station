package dev.aies.control.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningRevisionStoreTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper();
    private PlanningContracts.Intake intake(String id,String objective) {
        var s=new PlanningContracts.Source("src1","artifact://spec/task","a".repeat(64),"Task API request");
        var r=new PlanningContracts.Requirement("r1","Build API","src1",PlanningContracts.Verification.TEST,
            "valid JSON","POST /tasks","HTTP 201 persisted","software",List.of(),false);
        return new PlanningContracts.Intake("1.0",id,objective,"software",List.of(s),List.of(r),
            List.of("No external writes"),List.of(),10);
    }
    private void rejectedParent(Path db)throws Exception {
        try(var s=new PlanningStore(db,mapper)){
            var in=intake("wf-parent","Original objective");
            s.create(in);
            var event=s.pending(10).getFirst();
            var plan=PlanningPolicy.build(in,Map.of("r1",new PlanningContracts.Task(
                "task-r1","r1","software",List.of(),"software-engineer",3,"Build API",
                "valid JSON","POST /tasks","HTTP 201 persisted",PlanningContracts.Verification.TEST)));
            assertTrue(s.complete(event.id(),plan));
            assertTrue(s.attachArtifact("wf-parent","artifact-parent"));
            assertTrue(s.attachAudit("wf-parent","audit-parent","REVISION_REQUIRED"));
        }
    }
    @Test void createsImmutableChildAndReplaysAfterRestart()throws Exception {
        Path db=temp.resolve("planning.sqlite");rejectedParent(db);
        var request=new PlanningRevisionStore.Request("request-1","audit-parent",intake("wf-child","Improved objective"));
        try(var s=new PlanningRevisionStore(db,mapper)){
            var result=s.revise("wf-parent",request);
            assertEquals(1,result.depth());assertFalse(result.replayed());
            assertTrue(s.revise("wf-parent",request).replayed());
        }
        try(var s=new PlanningRevisionStore(db,mapper)){
            assertTrue(s.revise("wf-parent",request).replayed());
            assertEquals("wf-child",s.find("request-1").orElseThrow().workflowId());
            assertThrows(IllegalStateException.class,()->s.revise("wf-parent",
                new PlanningRevisionStore.Request("request-1","audit-parent",intake("wf-other","Changed"))));
        }
        try(var s=new PlanningStore(db,mapper)){
            assertEquals("REVISION_REQUIRED",s.find("wf-parent").orElseThrow().status());
            assertEquals("audit-parent",s.find("wf-parent").orElseThrow().auditRecordId());
            assertEquals("RECEIVED",s.find("wf-child").orElseThrow().status());
            assertNull(s.find("wf-child").orElseThrow().auditRecordId());
            assertEquals(1,s.pending(10).size());
        }
    }
    @Test void failsClosedOnStaleAuditAndEmptyRevision()throws Exception {
        Path db=temp.resolve("planning.sqlite");rejectedParent(db);
        try(var s=new PlanningRevisionStore(db,mapper)){
            assertThrows(IllegalStateException.class,()->s.revise("wf-parent",
                new PlanningRevisionStore.Request("request-2","wrong-audit",intake("wf-child","Improved objective"))));
            assertThrows(IllegalArgumentException.class,()->s.revise("wf-parent",
                new PlanningRevisionStore.Request("request-3","audit-parent",intake("wf-child","Original objective"))));
            assertTrue(s.find("request-2").isEmpty());
            assertTrue(s.find("request-3").isEmpty());
        }
        try(var s=new PlanningStore(db,mapper)){
            assertTrue(s.find("wf-child").isEmpty());
            assertTrue(s.pending(10).isEmpty());
        }
    }
    @Test void rejectsChildIdCollisionWithoutChangingParent()throws Exception {
        Path db=temp.resolve("planning.sqlite");rejectedParent(db);
        try(var s=new PlanningRevisionStore(db,mapper)){
            s.revise("wf-parent",new PlanningRevisionStore.Request("request-a","audit-parent",intake("wf-child","Improved objective")));
            assertThrows(java.sql.SQLException.class,()->s.revise("wf-parent",
                new PlanningRevisionStore.Request("request-b","audit-parent",intake("wf-child","Other objective"))));
        }
        try(var s=new PlanningStore(db,mapper)){
            assertEquals("REVISION_REQUIRED",s.find("wf-parent").orElseThrow().status());
            assertEquals(1,s.pending(10).size());
        }
    }
}
