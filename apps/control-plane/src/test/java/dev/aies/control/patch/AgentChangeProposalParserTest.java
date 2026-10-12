package dev.aies.control.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentChangeProposalParserTest {
    @Test void rejectsExtraFieldsAndInvalidHashes() {
        var parser=new AgentChangeProposalParser(new ObjectMapper());
        assertThrows(IllegalArgumentException.class,()->parser.parse("{\"changes\":[{\"path\":\"App.java\",\"originalSha256\":\"bad\",\"replacement\":\"new\"}]}"));
        assertThrows(IllegalArgumentException.class,()->parser.parse("{\"changes\":[],\"approved\":true}"));
    }
}
