package dev.aies.control.science;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ScientificOutboxIntegrationTest {
 @TempDir Path temp;
 @Test void pendingSurvivesRestartAndInboxIsIdempotent() throws Exception {
  Path db=temp.resolve("science.sqlite");String id;
  var plan=new ScientificContracts.Plan("1.0","wf-durable","Reliable experiment","software",
   List.of(new ScientificContracts.Hypothesis("h","claim","null","latency","no improvement","baseline","30",List.of())),
   List.of(new ScientificContracts.Experiment("e","h","benchmark","latency","compare","100",List.of())),List.of());
  try(var s=new ScientificWorkflowStore(db,new ObjectMapper())) {
   assertEquals("PLANNED",s.create(plan).status());
   assertEquals(1,s.pending(10).size());
   id=s.pending(10).getFirst().id();
  }
  try(var s=new ScientificWorkflowStore(db,new ObjectMapper())) {
   assertEquals("PLANNED",s.find("wf-durable").orElseThrow().status());
   assertTrue(s.acknowledge(id,"science-consumer"));
   assertFalse(s.acknowledge(id,"science-consumer"));
   assertEquals("READY_FOR_EXPERIMENT",s.find("wf-durable").orElseThrow().status());
   assertTrue(s.pending(10).isEmpty());
   assertThrows(IllegalArgumentException.class,()->s.pending(0));
  }
  try(var s=new ScientificWorkflowStore(db,new ObjectMapper())) {
   assertEquals("READY_FOR_EXPERIMENT",s.find("wf-durable").orElseThrow().status());
   assertTrue(s.pending(10).isEmpty());
  }
 }
}
