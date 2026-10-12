package dev.aies.control.communication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class SemanticAuditContractTest {
 private CommunicationStore.StoredRecord subject() {
  var a=new CommunicationContracts.Artifact("1.0","wf-a","scientific-plan","researcher","scientific-plan","text/markdown",
   "# Plan\\n"+"Method, controls, null hypothesis and decision criteria. ".repeat(4),List.of());
  return new CommunicationStore.StoredRecord("rec-a","wf-a","scientific-plan","ARTIFACT","1.0","hash","now",a);
 }
 @Test void unavailableModelFailsClosed() {
  var verdict=SemanticAuditPolicy.review(subject(),new ObjectMapper(),"https://remote.example","test",2);
  assertFalse(verdict.approved());assertFalse(verdict.findings().isEmpty());
 }
 @Test void stageRubricIsSentAndValidModelContractPasses() throws Exception {
  var prompt=new AtomicReference<String>("");
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/api/generate", exchange -> {
   var input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
   prompt.set(new ObjectMapper().readTree(input).path("prompt").asText());
   byte[] bytes=new ObjectMapper().writeValueAsBytes(java.util.Map.of("response", """
     {"approved":true,"findings":[]}
     """));
   exchange.getResponseHeaders().set("Content-Type","application/json");
   exchange.sendResponseHeaders(200,bytes.length);
   try(var out=exchange.getResponseBody()){out.write(bytes);}
  });
  server.start();
  try {
   var result=SemanticAuditPolicy.review(subject(),new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"mock",3);
   assertTrue(prompt.get().contains("PRE-EXPERIMENT PLAN"),"Stage rubric must reach Ollama prompt");
   assertTrue(result.approved(),"Valid model contract should be approved");
  } finally {server.stop(0);}
 }
}
