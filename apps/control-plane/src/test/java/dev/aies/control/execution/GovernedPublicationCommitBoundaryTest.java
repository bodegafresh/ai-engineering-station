package dev.aies.control.execution;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class GovernedPublicationCommitBoundaryTest {
 @TempDir Path temp;
 @Test void committedButAcknowledgementLostMustReconcile() throws Exception {
  Path path=temp.resolve("policy.sqlite");
  var request=new GovernedToolResourcePolicy.Request("wf","task","attempt","engineer","1.0","workspace-documentation","invoke-model","ollama:software-engineer");
  var snapshot=new GovernedToolResourcePolicy.Snapshot("wf","task","attempt","engineer","1.0",7,Instant.parse("2099-01-01T00:00:00Z"),false,List.of(new GovernedToolResourcePolicy.Grant("workspace-documentation","invoke-model","ollama:software-engineer")));
  try(var setup=new GovernedToolResourcePolicyStore(path)){setup.approve(snapshot);}
  AtomicBoolean once=new AtomicBoolean(true);
  try(var store=new GovernedToolResourcePolicyStore(path,()->{if(once.getAndSet(false))throw new SQLException("lost commit acknowledgement");})){
   assertThrows(SQLException.class,()->store.admitPublication(request));
  }
  try(var reopened=new GovernedToolResourcePolicyStore(path)){
   assertTrue(reopened.publicationAdmitted("wf","task","attempt"));
   assertEquals(1,reopened.auditTrail("wf","task","attempt").stream().filter(e->"PUBLICATION_ADMISSION".equals(e.type())&&"ALLOW".equals(e.decision())).count());
   assertThrows(SecurityException.class,()->reopened.admitPublication(request));
   assertEquals(1,reopened.auditTrail("wf","task","attempt").stream().filter(e->"PUBLICATION_ADMISSION".equals(e.type())&&"ALLOW".equals(e.decision())).count());
  }
 }
}
