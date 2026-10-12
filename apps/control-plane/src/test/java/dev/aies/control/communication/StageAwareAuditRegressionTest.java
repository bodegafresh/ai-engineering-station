package dev.aies.control.communication;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class StageAwareAuditRegressionTest {
 private CommunicationStore.StoredRecord artifact(String kind) {
  var a=new CommunicationContracts.Artifact("1.0","wf-stage","scientific-plan","researcher",kind,"text/markdown","# Title\n"+"Evidence and method. ".repeat(8),List.of());
  return new CommunicationStore.StoredRecord("record","wf-stage","scientific-plan","ARTIFACT","1.0","hash","now",a);
 }
 @Test void plansDoNotRequireFutureResults() {
  assertEquals(AuditStagePolicy.Stage.PLAN,AuditStagePolicy.classify(artifact("scientific-plan")));
  String rubric=AuditStagePolicy.rubric(AuditStagePolicy.Stage.PLAN);
  assertTrue(rubric.contains("DO NOT require experimental results"));
  assertEquals(AuditStagePolicy.Stage.EXPERIMENT_REPORT,AuditStagePolicy.classify(artifact("experiment-results")));
  assertTrue(AuditStagePolicy.rubric(AuditStagePolicy.Stage.EXPERIMENT_REPORT).contains("observed data"));
  assertEquals(AuditStagePolicy.Stage.SOFTWARE_IMPLEMENTATION,AuditStagePolicy.classify(artifact("code-implementation")));
  assertEquals(AuditStagePolicy.Stage.BUSINESS_VALIDATION,AuditStagePolicy.classify(artifact("market-validation")));
 }
}
