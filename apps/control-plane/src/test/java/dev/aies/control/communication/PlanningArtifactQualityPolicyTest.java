package dev.aies.control.communication;

import dev.aies.control.planning.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanningArtifactQualityPolicyTest {
    private PlanningContracts.Intake intake() {
        var r=new PlanningContracts.Requirement("r1","Create task API","src1",PlanningContracts.Verification.TEST,
            "valid request","POST /tasks","201 and persisted ID","software",List.of(),false);
        return new PlanningContracts.Intake("1.0","wf-g10b","Certify planning quality","software",
            List.of(new PlanningContracts.Source("src1","artifact://spec/task","a".repeat(64),"Task specification")),
            List.of(r),List.of(),List.of(),10);
    }
    private CommunicationContracts.Artifact artifact() throws Exception {
        var in=intake(); var r=in.requirements().getFirst();
        var plan=PlanningPolicy.build(in,Map.of("r1",new PlanningContracts.Task("task-r1","r1","software",List.of(),
            "software-engineer",3,r.statement(),r.given(),r.when(),r.then(),r.verification())));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        return PlanningArtifactEvidence.artifact(in,plan,"# Planning contract\n\n## Requirements and task assignments\n\n"
            +mapper.writerWithDefaultPrettyPrinter().writeValueAsString(plan)
            +"\n\n## State\nPRE-EXECUTION PLAN. No implementation or empirical results claimed.\n");
    }
    private CommunicationContracts.Artifact changed(CommunicationContracts.Artifact a,String content,List<String> refs) {
        return new CommunicationContracts.Artifact(a.schemaVersion(),a.workflowId(),a.taskId(),a.producer(),
            a.kind(),a.contentType(),content,refs);
    }
    @Test void validPlanIsStructurallyApproved() throws Exception {
        assertTrue(PlanningArtifactQualityPolicy.findings(artifact()).isEmpty());
    }
    @Test void missingSourceFailsClosed() throws Exception {
        var a=artifact();assertFalse(PlanningArtifactQualityPolicy.findings(changed(a,a.content(),List.of())).isEmpty());
    }
    @Test void changedTaskAcceptanceFailsClosed() throws Exception {
        var a=artifact();var c=a.content().replace("201 and persisted ID","not verified");
        // Both requirement and task are changed by global replace: still consistent; corrupt only one occurrence.
        int at=c.indexOf("not verified");c=c.substring(0,at)+"tampered"+c.substring(at+"not verified".length());
        assertFalse(PlanningArtifactQualityPolicy.findings(changed(a,c,a.sourceRefs())).isEmpty());
    }
    @Test void budgetMismatchFailsClosed() throws Exception {
        var a=artifact();var c=a.content().replace("\"estimatedCostUnits\" : 3","\"estimatedCostUnits\" : 100");
        assertFalse(PlanningArtifactQualityPolicy.findings(changed(a,c,a.sourceRefs())).isEmpty());
    }
    @Test void invalidPlanJsonFailsClosed() throws Exception {
        var a=artifact();assertFalse(PlanningArtifactQualityPolicy.findings(changed(a,"# Planning\n## Requirements and task assignments\nnot json\n## State",a.sourceRefs())).isEmpty());
    }
}
