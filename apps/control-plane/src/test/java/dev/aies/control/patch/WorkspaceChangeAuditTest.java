package dev.aies.control.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceChangeAuditTest {
    @TempDir Path tmp;
    @Test void stateAndAuditArePersistedTogether() throws Exception {
        Path db=tmp.resolve("journal.sqlite");
        String id=java.util.UUID.randomUUID().toString();
        try(var repo=new SqliteWorkspaceChangeRepository(db)) {
            var p=new WorkspaceChangeService.Proposal(id,"sample","PREPARED",null,"fingerprint",
                    List.of(),Instant.now());
            repo.create(p);
            assertTrue(repo.transition(id,"PREPARED","APPROVED","reviewer"));
            assertFalse(repo.transition(id,"PREPARED","APPLYING","reviewer"));
            assertEquals("APPROVED",repo.find(id).status());
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);
            var statement=c.prepareStatement("SELECT count(*) FROM workspace_change_event WHERE proposal_id=?")) {
            statement.setString(1,id);
            try(var rs=statement.executeQuery()) {assertEquals(2,rs.getInt(1));}
        }
    }
}
