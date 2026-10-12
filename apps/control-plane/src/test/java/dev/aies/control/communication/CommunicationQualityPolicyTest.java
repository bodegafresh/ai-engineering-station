package dev.aies.control.communication;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CommunicationQualityPolicyTest {
    @Test void flagsWeakArtifact() {
        var a=new CommunicationContracts.Artifact("1.0","wf","task","researcher","report",
            "text/markdown","Short text",List.of());
        var r=new CommunicationStore.StoredRecord("id","wf","task","ARTIFACT","1.0","hash","now",a);
        assertEquals(2,CommunicationQualityPolicy.findings(r).size());
    }
    @Test void approvesWellStructuredArtifact() {
        var a=new CommunicationContracts.Artifact("1.0","wf","task","researcher","report",
            "text/markdown","# Goals\n"+"A sufficiently detailed explanation of the requirements, assumptions, acceptance criteria and expected results. ".repeat(2),List.of());
        var r=new CommunicationStore.StoredRecord("id","wf","task","ARTIFACT","1.0","hash","now",a);
        assertTrue(CommunicationQualityPolicy.findings(r).isEmpty());
    }
}
