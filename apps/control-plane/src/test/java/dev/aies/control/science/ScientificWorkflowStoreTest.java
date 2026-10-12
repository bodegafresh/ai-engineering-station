package dev.aies.control.science;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ScientificWorkflowStoreTest {
    @TempDir Path temp;
    private static ScientificContracts.Plan plan() {
        return new ScientificContracts.Plan("1.0","wf-science-1","Reduce software defects","software",
            List.of(new ScientificContracts.Hypothesis("h1","Review lowers defects","Review does not lower defects",
                "Defect rate","No improvement relative to baseline","Prior defect rate","At least 30 independent changes",List.of("Comparable changes"))),
            List.of(new ScientificContracts.Experiment("e1","h1","Controlled comparison","Defects per change",
                "Lower than baseline with uncertainty estimate","2 hours",List.of("Same test suite"))),List.of());
    }
    @Test void outboxAndInboxSurviveRestartAndDeduplicate() throws Exception {
        var path=temp.resolve("science.sqlite");
        String eventId;
        try(var store=new ScientificWorkflowStore(path,new ObjectMapper())) {
            assertEquals("PLANNED",store.create(plan()).status());
            assertThrows(java.sql.SQLException.class,()->store.create(plan()));
            assertEquals(1,store.pending(10).size());
            eventId=store.pending(10).getFirst().id();
        }
        try(var store=new ScientificWorkflowStore(path,new ObjectMapper())) {
            assertTrue(store.acknowledge(eventId,"science-plan-processor"));
            assertFalse(store.acknowledge(eventId,"science-plan-processor"));
            assertEquals("READY_FOR_EXPERIMENT",store.find("wf-science-1").orElseThrow().status());
            assertTrue(store.pending(10).isEmpty());
            assertTrue(store.recordEvidence("wf-science-1","e1",0.15,30,"Measured on test dataset"));
            assertEquals(1,store.evidenceCount("wf-science-1"));
        }
    }
    @Test void invalidPlansAndMetricsFailClosed() throws Exception {
        var p=plan();
        assertThrows(IllegalArgumentException.class,()->ScientificContracts.validate(new ScientificContracts.Plan(
            "1.0",p.workflowId(),p.objective(),p.domain(),p.hypotheses(),List.of(),List.of())));
        try(var store=new ScientificWorkflowStore(temp.resolve("another.sqlite"),new ObjectMapper())) {
            store.create(p);
            assertThrows(IllegalArgumentException.class,()->store.recordEvidence(p.workflowId(),"e1",Double.NaN,10,"bad"));
            assertThrows(IllegalArgumentException.class,()->store.recordEvidence(p.workflowId(),"unknown",0.5,10,"bad"));
        }
    }
}
