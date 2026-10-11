package com.paicli.provider;

import com.paicli.context.ProviderRetryTestScope;
import com.paicli.credential.ProviderCredentialSource;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.TelemetryModelProvider;
import com.paicli.state.ProviderCallTelemetryStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleTransportRetryTest {
    private HttpServer server;

    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void TRANSPORT_CONNECT_TIMEOUT_USES_TRANSPORT_DOUBLE()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        OpenAICompatibleProvider raw=provider(request->{
            if(calls.incrementAndGet()==1)throw new HttpConnectTimeoutException("timeout");
            return response(request,200,complete("restored"),Map.of());
        });
        try(AutoCloseable ignored=ProviderRetryTestScope.open()){
            assertEquals("restored",telemetry(raw).chat(
                    List.of(AgentMessage.userGoal("bounded")),List.of()).content());
        }
        assertEquals(2,calls.get(),"PASS: deterministic connect timeout retried once");
    }

    @Test void TRANSPORT_TLS_EOF_IS_DISCARDED_BEFORE_RETRY()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        OpenAICompatibleProvider raw=provider(request->{
            if(calls.incrementAndGet()==1)throw new SSLException("handshake EOF");
            return response(request,200,complete("complete"),Map.of());
        });
        try(AutoCloseable ignored=ProviderRetryTestScope.open()){
            ModelResponse result=telemetry(raw).chat(
                    List.of(AgentMessage.userGoal("bounded")),List.of());
            assertEquals("complete",result.content());
        }
        assertEquals(2,calls.get(),"PASS: incomplete TLS attempt produced no public response");
    }

    @Test void TRANSPORT_429_RETRY_AFTER_LOOPBACK_PASS()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();
            if(calls.incrementAndGet()==1){
                exchange.getResponseHeaders().add("Retry-After","0");
                exchange.sendResponseHeaders(429,-1);exchange.close();return;
            }
            byte[] body=complete("ok").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        OpenAICompatibleProvider raw=new OpenAICompatibleProvider(
                "http://localhost:"+server.getAddress().getPort(),"test-key","model",
                "system",5);
        try(AutoCloseable ignored=ProviderRetryTestScope.open()){
            assertEquals("ok",telemetry(raw).chat(
                    List.of(AgentMessage.userGoal("bounded")),List.of()).content());
        }
        assertEquals(2,calls.get(),"PASS: server observed exactly two HTTP requests");
    }

    @Test void TRANSPORT_401_LOOPBACK_NO_RETRY()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401,-1);exchange.close();
        });server.start();
        OpenAICompatibleProvider raw=new OpenAICompatibleProvider(
                "http://localhost:"+server.getAddress().getPort(),"test-key","model",
                "system",5);
        ModelResponse result=telemetry(raw).chat(
                List.of(AgentMessage.userGoal("bounded")),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE,
                result.providerErrorKind());
        assertEquals(1,calls.get(),"PASS: 401 server observed one request");
    }

    @Test void TRANSPORT_PARTIAL_BODY_LOOPBACK_DISCARDED()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try(ServerSocket listener=new ServerSocket(0)){
            Thread serverThread=Thread.ofPlatform().daemon(true).start(()->{
                try{
                    try(Socket first=listener.accept()){
                        readRequest(first);
                        byte[] partial="{\"choices\":[{\"message\":{".getBytes(StandardCharsets.UTF_8);
                        first.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 999\r\n"
                                +"Content-Type: application/json\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        first.getOutputStream().write(partial);first.getOutputStream().flush();
                        calls.incrementAndGet();
                    }
                    try(Socket second=listener.accept()){
                        readRequest(second);byte[] body=complete("complete")
                                .getBytes(StandardCharsets.UTF_8);
                        second.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: "
                                +body.length+"\r\nContent-Type: application/json\r\n"
                                +"Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        second.getOutputStream().write(body);second.getOutputStream().flush();
                        calls.incrementAndGet();
                    }
                }catch(IOException failure){throw new AssertionError(failure);}
            });
            OpenAICompatibleProvider raw=new OpenAICompatibleProvider(
                    "http://localhost:"+listener.getLocalPort(),"test-key","model","system",5);
            try(AutoCloseable ignored=ProviderRetryTestScope.open()){
                ModelResponse result=telemetry(raw).chat(
                        List.of(AgentMessage.userGoal("bounded")),List.of());
                assertEquals("complete",result.content());
            }
            serverThread.join(5_000);
            assertFalse(serverThread.isAlive());
            assertEquals(2,calls.get(),"PASS: truncated first body was discarded and retried");
        }
    }

    private static OpenAICompatibleProvider provider(
            OpenAICompatibleProvider.HttpTransport transport){
        return new OpenAICompatibleProvider("http://localhost:1",
                ProviderCredentialSource.fixed("test-key"),"model","system",5,
                OpenAICompatiblePreset.generic(),"disabled",null,transport);
    }

    private static TelemetryModelProvider telemetry(OpenAICompatibleProvider raw){
        return new TelemetryModelProvider(raw,new NoopStore(),"openai","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                ()->new ProviderCallContext("session","task","run","ctx-test"));
    }

    private static String complete(String content){
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                +content+"\"}}]}";
    }

    private static void readRequest(Socket socket)throws IOException{
        var input=socket.getInputStream();int state=0;StringBuilder headers=new StringBuilder();
        while(state<4){
            int value=input.read();if(value<0)throw new IOException("request headers ended early");
            headers.append((char)value);
            state=switch(state){
                case 0->value=='\r'?1:0;
                case 1->value=='\n'?2:0;
                case 2->value=='\r'?3:0;
                default->value=='\n'?4:0;
            };
        }
        int length=headers.toString().lines()
                .filter(line->line.toLowerCase().startsWith("content-length:"))
                .map(line->line.substring(line.indexOf(':')+1).trim())
                .mapToInt(Integer::parseInt).findFirst().orElse(0);
        input.readNBytes(length);
    }

    private static HttpResponse<String> response(HttpRequest request,int status,String body,
            Map<String,List<String>> headers){
        return new StubResponse(request,status,body,HttpHeaders.of(headers,(a,b)->true));
    }

    private record StubResponse(HttpRequest request,int statusCode,String body,
            HttpHeaders headers) implements HttpResponse<String>{
        @Override public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
        @Override public URI uri(){return request.uri();}
        @Override public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
        @Override public Optional<SSLSession> sslSession(){return Optional.empty();}
    }

    private static final class NoopStore extends ProviderCallTelemetryStore{
        private NoopStore(){super(Path.of("."));}
        @Override public boolean recordStarted(CallStart start){return true;}
        @Override public boolean linkContext(String correlation,String generation){return true;}
        @Override public boolean finalizeCall(CallFinish finish){return true;}
    }
}
