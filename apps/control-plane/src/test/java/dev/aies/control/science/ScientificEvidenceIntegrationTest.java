package dev.aies.control.science;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ScientificEvidenceIntegrationTest {
 @TempDir Path temp;
 private ScientificContracts.Plan plan(String id) {
  return new ScientificContracts.Plan("1.0",id,"Benchmark performance","software",
   List.of(new ScientificContracts.Hypothesis("h1","Faster","Not faster","duration","No reduction","20 seconds","30",List.of("same hardware"))),
   List.of(new ScientificContracts.Experiment("exp1","h1","Run comparison","durationSeconds","Compare confidence intervals","60 runs",List.of("same load"))),List.of());
 }
 @Test void durableObservationsSourceAndCrossWorkflowRejection() throws Exception {
  Path db=temp.resolve("science.sqlite");
  try(var workflows=new ScientificWorkflowStore(db,new ObjectMapper())) {workflows.create(plan("wf-a"));workflows.create(plan("wf-b"));}
  try(var evidence=new ResearchEvidenceStore(db,new ObjectMapper())) {
   assertThrows(IllegalArgumentException.class,()->evidence.addObservation("wf-a","missing",true,1,2,"note"));
   assertThrows(IllegalArgumentException.class,()->evidence.addObservation("missing","exp1",true,1,2,"note"));
   assertThrows(IllegalArgumentException.class,()->evidence.addObservation("wf-a","exp1",true,Double.NaN,2,"note"));
   assertThrows(IllegalArgumentException.class,()->evidence.addObservation("wf-a","exp1",true,1,-1,"note"));
   assertThrows(IllegalArgumentException.class,()->evidence.addSource(new ResearchEvidence.Source("wf-a","exp1","Source","http://example.org","2026-10-12","Excerpt","a".repeat(64),"manual",List.of())));
   evidence.addObservation("wf-a","exp1",true,0.2,15,"first trial");
   evidence.addObservation("wf-a","exp1",false,0.3,18,"second trial");
   evidence.addSource(new ResearchEvidence.Source("wf-a","exp1","Source","https://example.org","2026-10-12","Excerpt","a".repeat(64),"manual",List.of("not independently verified")));
  }
  try(var evidence=new ResearchEvidenceStore(db,new ObjectMapper())) {
   assertEquals(2,evidence.observations("wf-a").size());
   assertEquals(1,evidence.sources("wf-a").size());
   assertTrue(evidence.observations("wf-b").isEmpty());
   var report=ScientificRetrospective.calculate("wf-a",evidence.sources("wf-a"),evidence.observations("wf-a"));
   assertEquals(2,report.observationCount());
   assertEquals(1,report.sourceCount());
   assertEquals(0.5,report.experiments().getFirst().successRate(),1e-12);
   assertEquals(0.5,report.experiments().getFirst().totalCost(),1e-12);
   assertEquals("INSUFFICIENT_SAMPLE",report.experiments().getFirst().recommendation());
   assertThrows(IllegalArgumentException.class,()->ScientificRetrospective.calculate("wf-b",evidence.sources("wf-a"),evidence.observations("wf-a")));
  }
 }
}
