package dev.aies.control;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import static org.junit.jupiter.api.Assertions.*;
class LocalModelClientTest {
    @Test void readsChatCompletionFromLocalMock() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            byte[] body="{\"model\":\"mock\",\"choices\":[{\"message\":{\"content\":\"Hola desde el modelo\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var os=exchange.getResponseBody()){os.write(body);}
        });
        server.start();
        try {
            var client=new LocalModelClient(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort(),"mock",5);
            var answer=client.complete("Di hola");
            assertEquals("Hola desde el modelo",answer.content());
            assertEquals("local:mock",answer.model());
        } finally {server.stop(0);}
    }
    @Test void rejectsNonLocalEndpoint() {
        assertThrows(IllegalArgumentException.class,()->new LocalModelClient(new ObjectMapper(),"http://example.com","model",5));
    }
}
