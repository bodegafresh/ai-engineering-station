package dev.aies.control.communication;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aies.control.science.ScientificDeliveryPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AuditDeliveryIntegrationTest {
 @TempDir Path temp;
 @Test void immutableRevisionCannotInheritApprovalAndHistoryPersists() throws Exception {
  Path db=temp.resolve("communication.sqlite");
  String first,second;
  try(var store=new CommunicationStore(db,new ObjectMapper())) {
   var a=artifact("# Plan\n"+"Detailed scientific method, hypothesis, baseline, controls and measurable acceptance criteria. ".repeat(3));
   first=store.append("ARTIFACT","wf-gate","scientific-plan",a).id();
   assertFalse(ScientificDeliveryPolicy.approved(first,store.list("wf-gate")));
   store.append("AUDIT","wf-gate","scientific-plan",audit(first,CommunicationContracts.AuditDecision.REVISION_REQUIRED,1));
   assertFalse(ScientificDeliveryPolicy.approved(first,store.list("wf-gate")));
   second=store.append("ARTIFACT","wf-gate","scientific-plan",artifact(a.content()+"\nRevised.")).id();
   assertNotEquals(first,second);
   store.append("AUDIT","wf-gate","scientific-plan",audit(second,CommunicationContracts.AuditDecision.APPROVED,2));
   assertFalse(ScientificDeliveryPolicy.approved(first,store.list("wf-gate")));
   assertTrue(ScientificDeliveryPolicy.approved(second,store.list("wf-gate")));
   assertFalse(ScientificDeliveryPolicy.approved("not-a-record",store.list("wf-gate")));
  }
  try(var store=new CommunicationStore(db,new ObjectMapper())) {
   assertEquals(4,store.list("wf-gate").size());
   assertFalse(ScientificDeliveryPolicy.approved(first,store.list("wf-gate")));
   assertTrue(ScientificDeliveryPolicy.approved(second,store.list("wf-gate")));
   assertTrue(store.find(first).isPresent());
  }
 }
 private static CommunicationContracts.Artifact artifact(String content) {
  return new CommunicationContracts.Artifact("1.0","wf-gate","scientific-plan","researcher","scientific-plan","text/markdown",content,List.of());
 }
 private static CommunicationContracts.Audit audit(String subject,CommunicationContracts.AuditDecision decision,int iteration) {
  return new CommunicationContracts.Audit("1.0","wf-gate",subject,"auditor",decision,iteration,3,List.of(),List.of());
 }
}
