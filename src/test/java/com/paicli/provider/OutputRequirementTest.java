package com.paicli.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.*;
import com.paicli.provider.telemetry.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputRequirementTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final OutputRequirement NATIVE=OutputRequirement.managed(OutputRequirement.Mode.NATIVE_REQUIRED);
    static final OutputRequirement CANDIDATE=OutputRequirement.managed(OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE);
    static final OutputRequirement PROMPT=OutputRequirement.managed(OutputRequirement.Mode.PROMPT_ONLY);
    static final List<AgentMessage> MESSAGES=List.of(AgentMessage.userGoal("Return JSON {\"userAnswer\":\"ok\"}"));
    @TempDir Path work;
    HttpServer server;
    final List<byte[]> received=new ArrayList<>();
    final AtomicReference<String> response=new AtomicReference<>();
    final AtomicInteger status=new AtomicInteger(200);
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    OpenAICompatibleProvider start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            received.add(exchange.getRequestBody().readAllBytes());
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
        var config=JSON.readValue("{\"provider\":\"openai\",\"provider_preset\":\"deepseek\"}",Config.class);
        return new OpenAICompatibleProvider("http://127.0.0.1:"+server.getAddress().getPort(),
                "fixture-only","deepseek-v4-flash","",5,OpenAICompatiblePreset.from(config),"disabled",null);
    }
    static String finalBody(String finish)throws Exception{
        var choice=new LinkedHashMap<String,Object>();
        choice.put("message",Map.of("role","assistant","content","{\"userAnswer\":\"ok\"}"));
        if(finish!=null)choice.put("finish_reason",finish);
        return JSON.writeValueAsString(Map.of("choices",List.of(choice),"usage",
                Map.of("prompt_tokens",10,"completion_tokens",4,"total_tokens",14)));
    }
    static List<ToolDefinition> tools()throws Exception{
        return List.of(new ToolDefinition("read_file","read",JSON.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}")));
    }
    @Test void unsupportedAndUnknownJointFailBeforeTransportAndDecoratorsCannotDropRequirement()throws Exception{
        var provider=start();response.set(finalBody("stop"));
        assertThrows(IllegalArgumentException.class,()->provider.prepareTurn("CHAT",MESSAGES,tools(),NATIVE));
        var generic=new OpenAICompatibleProvider("http://localhost:1","unused","fixture","",1);
        assertThrows(IllegalArgumentException.class,()->generic.prepareTurn("CHAT",MESSAGES,List.of(),NATIVE));
        ModelProvider old=(m,t)->{fail("must not call legacy delegate");return null;};
        assertThrows(IllegalArgumentException.class,()->old.chatForOperation("CHAT",MESSAGES,List.of(),PROMPT));
        PreparedTurnProvider incomplete=new PreparedTurnProvider(){
            public void validateOutputRequirement(OutputRequirement r,boolean t){}
            public PreparedProviderTurn prepareTurn(String o,List<AgentMessage> m,List<ToolDefinition> t){fail("lost policy");return null;}
        };
        assertThrows(IllegalArgumentException.class,()->incomplete.prepareTurn("CHAT",MESSAGES,List.of(),PROMPT));
        assertTrue(received.isEmpty());
    }
    @Test void nativeRequestMetricsDigestAndRepeatedExecutionUseExactlyPreparedBytes()throws Exception{
        var provider=start();response.set(finalBody("stop"));
        var plain=provider.prepareTurn("CHAT",MESSAGES,List.of(),PROMPT);
        var nativeTurn=provider.prepareTurn("CHAT",MESSAGES,List.of(),NATIVE);
        assertNotEquals(plain.requestDigest(),nativeTurn.requestDigest());
        nativeTurn.execute();nativeTurn.execute();
        assertEquals(2,received.size());assertArrayEquals(received.get(0),received.get(1));
        assertEquals(com.paicli.skill.SkillPackageFiles.hash(received.getFirst()),nativeTurn.requestDigest());
        assertEquals(received.getFirst().length,nativeTurn.metrics().utf8Bytes());
        assertEquals(NATIVE,nativeTurn.metrics().requirement());
        assertEquals("json_object",JSON.readTree(received.getFirst()).path("response_format").path("type").asText());
        plain.execute();assertFalse(JSON.readTree(received.getLast()).has("response_format"),"JSON words never select the mechanism");
    }
    @Test void ledgerPreservesNativeMetadataAndSettlesIncompleteResponses()throws Exception{
        var adapter=start();response.set(finalBody("length"));
        Path path=work.resolve("calls.jsonl");
        var ledger=new AppendOnlyProviderCallLedger(path,work);
        var provider=new ProviderCallLedgerModelProvider(adapter,ledger,
                new ProviderCallLedgerContext("native-run","PAICLI","openai/deepseek","deepseek-v4-flash","a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->provider.prepareTurn("CHAT",MESSAGES,tools(),NATIVE));
        assertEquals(0,AppendOnlyProviderCallLedger.recover(path).exactCallCount());
        var turn=provider.prepareTurn("CHAT",MESSAGES,List.of(),NATIVE);
        assertEquals(NATIVE,turn.metrics().requirement());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_INCOMPLETE,turn.execute().providerErrorKind());
        assertEquals(14,provider.takeObservation().totalTokens());
        assertEquals(1,received.size());assertEquals(1,AppendOnlyProviderCallLedger.recover(path).exactCallCount());
        assertEquals(com.paicli.skill.SkillPackageFiles.hash(received.getFirst()),turn.requestDigest());
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"length","content_filter","insufficient_system_resource","other","tool_calls"})
    void incompleteLegalJsonKeepsUsageAndNeverBecomesFinal(String finish)throws Exception{
        var provider=start();response.set(finalBody(finish));
        for(var requirement:List.of(NATIVE,PROMPT)){
            var result=provider.chatForOperation("CHAT",MESSAGES,List.of(),requirement);
            assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_INCOMPLETE,result.providerErrorKind());
            var observed=provider.takeObservation();assertEquals(14,observed.totalTokens());
            assertEquals("PROVIDER_RESPONSE_INCOMPLETE",observed.errorCategory());
            assertEquals(ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED,provider.takeTransportEvidence().usageProvenance());
            assertNull(provider.takeRetrySignal());
        }
        assertEquals(2,received.size());
    }
    @Test void toolCommentaryIsNotFinalJsonAndContinuationKeepsTheNativeRequirement()throws Exception{
        var provider=start();
        response.set("""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"Reading now",
                "tool_calls":[{"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"a.txt\\"}"}}]}}],
                "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                """);
        var first=provider.chatForOperation("CHAT",MESSAGES,tools(),CANDIDATE);
        assertEquals(ModelResponse.ResponseType.TOOL_CALL,first.type());assertEquals("Reading now",first.content());
        var history=new ArrayList<>(MESSAGES);
        history.add(AgentMessage.actionWithToolCall(first.content(),first.toolCall().providerCallId(),
                JSON.writeValueAsString(Map.of("name",first.toolCall().name(),"arguments",first.toolCall().arguments()))));
        history.add(AgentMessage.observationForToolCall("file content",first.toolCall().providerCallId()));
        response.set(finalBody("stop"));
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation("TOOL_CONTINUATION",history,tools(),CANDIDATE).type());
        for(byte[] bytes:received)assertEquals("json_object",JSON.readTree(bytes).path("response_format").path("type").asText());
        assertEquals("call-1",JSON.readTree(received.getLast()).path("messages").get(2).path("tool_call_id").asText());
    }
    @ParameterizedTest @ValueSource(strings={"length","stop","missing","unknown","false"})
    void ollamaManagedCompletenessIsExplicitWhileDirectLegacyIsPreserved(String finish)throws Exception{
        start();
        var body=new LinkedHashMap<String,Object>();body.put("message",Map.of("content","{\"userAnswer\":\"ok\"}"));
        body.put("done",!finish.equals("false"));if(!finish.equals("missing"))body.put("done_reason",finish);
        body.put("prompt_eval_count",10);body.put("eval_count",4);response.set(JSON.writeValueAsString(body));
        var provider=new OllamaProvider("http://127.0.0.1:"+server.getAddress().getPort(),"fixture","",5);
        assertThrows(IllegalArgumentException.class,()->provider.chatForOperation("CHAT",MESSAGES,List.of(),NATIVE));
        assertTrue(received.isEmpty());
        var result=provider.chatForOperation("CHAT",MESSAGES,List.of(),PROMPT);
        assertEquals(finish.equals("stop")?ModelResponse.ResponseType.FINAL:ModelResponse.ResponseType.PROVIDER_ERROR,result.type());
        assertEquals(14,provider.takeObservation().totalTokens());
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chat(MESSAGES,List.of()).type());
    }
}
