package dev.aies.control.planning;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class PlanningStoreIntegrationTest {
 @TempDir Path temp;
 @Test void persistsAcrossRestartsAndRejectsReplay()throws Exception{
  Path file=temp.resolve("planning.sqlite");String event;
  var in=PlanningPolicyTest.intake(List.of(PlanningPolicyTest.req("r1",List.of())),List.of(),10);
  try(var store=new PlanningStore(file,new ObjectMapper())){
   assertEquals("RECEIVED",store.create(in).status());
   assertThrows(Exception.class,()->store.create(in));
   assertEquals(1,store.pending(10).size());
   event=store.pending(10).getFirst().id();
  }
  var r=in.requirements().getFirst();
  var plan=PlanningPolicy.build(in,Map.of("r1",PlanningPolicyTest.task(r,3)));
  try(var store=new PlanningStore(file,new ObjectMapper())){
   assertTrue(store.complete(event,plan));
   assertFalse(store.complete(event,plan));
   assertTrue(store.pending(10).isEmpty());
   assertEquals("AUDIT_REQUIRED",store.find("wf-p0").orElseThrow().status());
   assertTrue(store.attachArtifact("wf-p0","artifact-1"));
   assertFalse(store.attachArtifact("wf-p0","artifact-2"));
   assertTrue(store.attachAudit("wf-p0","audit-1","READY_FOR_ASSIGNMENT"));
  }
  try(var store=new PlanningStore(file,new ObjectMapper())){
   var snapshot=store.find("wf-p0").orElseThrow();
   assertEquals("READY_FOR_ASSIGNMENT",snapshot.status());
   assertEquals("artifact-1",snapshot.artifactRecordId());
   assertEquals("audit-1",snapshot.auditRecordId());
   assertEquals(1,snapshot.plan().tasks().size());
  }
 }
 @Test void deadLettersAfterBoundedFailures()throws Exception{
  Path file=temp.resolve("failed.sqlite");
  var in=PlanningPolicyTest.intake(List.of(PlanningPolicyTest.req("r1",List.of())),List.of(),10);
  try(var store=new PlanningStore(file,new ObjectMapper())){
   store.create(in);var id=store.pending(10).getFirst().id();
   for(int i=0;i<5;i++)store.failed(id);
   assertTrue(store.pending(10).isEmpty());
   assertEquals("RECEIVED",store.find("wf-p0").orElseThrow().status());
  }
 }
}
