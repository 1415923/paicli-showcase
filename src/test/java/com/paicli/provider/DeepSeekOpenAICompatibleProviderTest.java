package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderTransportEvidence;
import com.paicli.provider.telemetry.TelemetryModelProvider;
import com.paicli.state.ProviderCallTelemetryStore;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekOpenAICompatibleProviderTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private HttpServer server;

    @AfterEach void stop(){if(server!=null)server.stop(0);}

    @Test void responseIdentityDistinguishesReturnedFromNotReturnedWithoutSynthesis()
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            String identity = calls.incrementAndGet() == 1
                    ? "\"id\":\"deepseek-response-1\"," : "";
            respond(exchange,200,"{" + identity + "\"choices\":[{\"finish_reason\":"
                    + "\"stop\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"done\"}}],\"usage\":{\"prompt_tokens\":1,"
                    + "\"completion_tokens\":1,\"total_tokens\":2}}");
        });
        server.start();
        OpenAICompatibleProvider provider=provider();

        provider.chat(List.of(AgentMessage.userGoal("first")),List.of());
        var returned=provider.takeObservation();
        provider.chat(List.of(AgentMessage.userGoal("second")),List.of());
        var absent=provider.takeObservation();

        assertEquals("deepseek-response-1",returned.safeRequestId());
        assertEquals(com.paicli.provider.telemetry.ProviderCallObservation
                        .ResponseIdentityProvenance.PROVIDER_RETURNED,
                returned.responseIdentityProvenance());
        assertNull(absent.safeRequestId(),"no response ID may be synthesized");
        assertEquals(com.paicli.provider.telemetry.ProviderCallObservation
                        .ResponseIdentityProvenance.PROVIDER_NOT_RETURNED,
                absent.responseIdentityProvenance());
    }

    @Test void malformedResponseClassifiesIdentityAsAdapterNotExposed() throws Exception {
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->respond(exchange,200,"not-json"));
        server.start();
        OpenAICompatibleProvider provider=provider();

        provider.chat(List.of(AgentMessage.userGoal("malformed")),List.of());
        var observation=provider.takeObservation();

        assertNull(observation.safeRequestId());
        assertEquals(com.paicli.provider.telemetry.ProviderCallObservation
                        .ResponseIdentityProvenance.ADAPTER_NOT_EXPOSED,
                observation.responseIdentityProvenance());
    }

    @Test void deepSeekPresetJoinsEndpointAndRequestsThinkingJsonPlan()throws Exception{
        AtomicReference<JsonNode> request=new AtomicReference<>();
        AtomicReference<String> auth=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"{\\"acceptanceCriteria\\":[\\"done\\"]}"}}],
                    "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse response=provider.chatForOperation("PLAN",
                List.of(AgentMessage.userGoal("Return JSON plan")),List.of());
        assertEquals(ModelResponse.ResponseType.FINAL,response.type());
        assertEquals("Bearer test-secret",auth.get());
        assertEquals("deepseek-v4-flash",request.get().path("model").asText());
        assertFalse(request.get().path("stream").asBoolean(true));
        assertEquals("enabled",request.get().path("thinking").path("type").asText());
        assertEquals("max",request.get().path("reasoning_effort").asText());
        assertEquals("json_object",request.get().path("response_format").path("type").asText());
        assertTrue(request.get().path("max_tokens").asInt()>0);
    }

    @Test void deepSeekToolResultContinuationPreservesProviderCallId()throws Exception{
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode body=JSON.readTree(exchange.getRequestBody());
            boolean hasTool=false;
            for(JsonNode message:body.path("messages"))if("tool".equals(message.path("role").asText()))
                hasTool=true;
            if(!hasTool)respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"private-reasoning-sentinel-1",
                    "tool_calls":[{"id":"call-deepseek-1","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],
                    "usage":{"prompt_tokens":8,"completion_tokens":3,"total_tokens":11}}
                    """);
            else{continuation.set(body);respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"README.md is present."}}],
                    "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                    """);}
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("list")),
                List.of(readFileDefinition()));
        assertEquals("call-deepseek-1",first.toolCall().providerCallId());
        String callJson="""
                {"id":"call-deepseek-1","type":"function",
                "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}
                """;
        ModelResponse second=provider.chat(List.of(AgentMessage.userGoal("list"),
                AgentMessage.actionWithToolCall("listing","call-deepseek-1",callJson),
                AgentMessage.observationForToolCall("README.md","call-deepseek-1")),
                List.of(readFileDefinition()));
        assertEquals(ModelResponse.ResponseType.FINAL,second.type());
        JsonNode messages=continuation.get().path("messages");
        assertTrue(java.util.stream.StreamSupport.stream(messages.spliterator(),false)
                .anyMatch(message->message.path("tool_calls").path(0).path("id")
                        .asText().equals("call-deepseek-1")));
        assertTrue(java.util.stream.StreamSupport.stream(messages.spliterator(),false)
                .anyMatch(message->message.path("tool_call_id").asText()
                        .equals("call-deepseek-1")));
        JsonNode assistant=java.util.stream.StreamSupport.stream(messages.spliterator(),false)
                .filter(message->message.path("tool_calls").path(0).path("id")
                        .asText().equals("call-deepseek-1")).findFirst().orElseThrow();
        assertEquals("",assistant.path("content").asText());
        assertEquals("private-reasoning-sentinel-1",
                assistant.path("reasoning_content").asText());
        assertFalse(second.toString().contains("private-reasoning-sentinel-1"));
    }

    @Test void thinkingDisabledRemainsExplicitAndDoesNotRequireReasoning()throws Exception{
        AtomicReference<JsonNode> request=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            request.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"done"}}]}
                    """);
        });server.start();
        Config config=deepSeekConfig().withThinking("disabled",null);
        OpenAICompatibleProvider provider=provider(config);
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation("CHAT",
                List.of(AgentMessage.userGoal("simple")),List.of()).type());
        assertEquals("disabled",request.get().path("thinking").path("type").asText());
        assertFalse(request.get().has("reasoning_effort"));
    }

    @Test void highEffortIsSerializedForFinalSynthesis()throws Exception{
        AtomicReference<JsonNode> request=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            request.set(JSON.readTree(exchange.getRequestBody()));
            respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"final"}}]}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider(deepSeekConfig().withThinking("enabled","high"));
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation(
                "FINAL_SYNTHESIS",List.of(AgentMessage.userGoal("finish")),List.of()).type());
        assertEquals("enabled",request.get().path("thinking").path("type").asText());
        assertEquals("high",request.get().path("reasoning_effort").asText());
        assertFalse(request.get().has("tool_choice"));
    }

    @Test void missingReasoningOnThinkingToolCallFailsWithoutLeakingContent()throws Exception{
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->respond(exchange,200,"""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                "content":"call tool","tool_calls":[{"id":"call-missing","type":"function",
                "function":{"name":"list_files","arguments":"{}"}}]}}]}
                """));server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("list")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind
                        .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE,
                response.providerErrorKind());
        assertFalse(response.toString().contains("call tool"));
        assertEquals("REASONING_CONTENT_ABSENT_FOR_TOOL_CONTINUATION",
                provider.takeObservation().errorCategory());
    }

    @Test void emptyReasoningIsValidAndSerializedUnchangedForContinuation()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            if(calls.incrementAndGet()==1)respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"","tool_calls":[{
                    "id":"call-empty-reasoning","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],
                    "usage":{"prompt_tokens":8,"completion_tokens":0,"total_tokens":8,
                    "completion_tokens_details":{"reasoning_tokens":0}}}
                    """);
            else{continuation.set(request);respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"grounded final"}}]}
                    """);}
        });server.start();
        CapturingTelemetryStore telemetry=new CapturingTelemetryStore();
        TelemetryModelProvider provider=new TelemetryModelProvider(provider(),telemetry,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty);
        List<ToolDefinition> tools=List.of(readFileDefinition());
        ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("read")),tools);

        assertEquals(ModelResponse.ResponseType.TOOL_CALL,response.type());
        assertEquals(1,calls.get(),"present-empty reasoning must not trigger retry");
        assertEquals(1,telemetry.started.get());
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation(
                "TOOL_CONTINUATION",List.of(AgentMessage.userGoal("read"),
                        action(response,"call-empty-reasoning"),
                        AgentMessage.observationForToolCall("evidence",
                                "call-empty-reasoning")),tools).type());
        JsonNode assistant=findAssistantTurn(continuation.get(),"call-empty-reasoning");
        assertTrue(assistant.has("reasoning_content"));
        assertTrue(assistant.path("reasoning_content").isTextual());
        assertEquals("",assistant.path("reasoning_content").textValue());
        assertEquals(2,calls.get());
    }

    @Test void boundedRetryDiscardsIncompleteTurnThenContinuesWithSecondCallIdentity()
            throws Exception{
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<JsonNode> firstRequest=new AtomicReference<>();
        AtomicReference<JsonNode> retryRequest=new AtomicReference<>();
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            int call=calls.incrementAndGet();
            if(call==1){
                firstRequest.set(request);respond(exchange,200,"""
                        {"id":"completion-incomplete","choices":[{"finish_reason":"tool_calls",
                        "message":{"role":"assistant","content":"","reasoning_content":null,
                        "tool_calls":[{"id":"call-discarded","type":"function",
                        "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],
                        "usage":{"prompt_tokens":20,"completion_tokens":3,"total_tokens":23,
                        "completion_tokens_details":{"reasoning_tokens":3}}}
                        """);
            }else if(call==2){
                retryRequest.set(request);respond(exchange,200,"""
                        {"id":"completion-valid","choices":[{"finish_reason":"tool_calls",
                        "message":{"role":"assistant","content":"",
                        "reasoning_content":"private-retry-sentinel",
                        "tool_calls":[{"id":"call-accepted","type":"function",
                        "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}]}
                        """);
            }else{
                continuation.set(request);respond(exchange,200,"""
                        {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                        "content":"grounded result"}}]}
                        """);
            }
        });server.start();
        CapturingTelemetryStore telemetry=new CapturingTelemetryStore();
        TelemetryModelProvider provider=new TelemetryModelProvider(provider(),telemetry,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty);
        List<AgentMessage> prompt=List.of(AgentMessage.userGoal("read README"));
        List<ToolDefinition> tools=List.of(readFileDefinition());

        ModelResponse admitted;
        try(AutoCloseable ignored=com.paicli.context.ProviderRetryTestScope.open()){
            admitted=provider.chatForOperation("CHAT",prompt,tools);
        }

        assertEquals(ModelResponse.ResponseType.TOOL_CALL,admitted.type());
        assertEquals("call-accepted",admitted.toolCall().providerCallId());
        assertEquals(firstRequest.get().path("messages"),retryRequest.get().path("messages"));
        assertEquals(firstRequest.get().path("tools"),retryRequest.get().path("tools"));
        assertEquals("enabled",retryRequest.get().path("thinking").path("type").asText());
        assertEquals("max",retryRequest.get().path("reasoning_effort").asText());
        assertFalse(retryRequest.get().has("tool_choice"));
        ModelResponse completed=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("read README"),action(admitted,"call-accepted"),
                AgentMessage.observationForToolCall("bounded evidence","call-accepted")),tools);
        assertEquals(ModelResponse.ResponseType.FINAL,completed.type());
        assertEquals(3,calls.get());
        assertEquals(3,telemetry.started.get());
        assertEquals(3,telemetry.finished.get());
        String continuationJson=continuation.get().toString();
        assertTrue(continuationJson.contains("call-accepted"));
        assertFalse(continuationJson.contains("call-discarded"));
        assertTrue(continuationJson.contains("private-retry-sentinel"));
        assertFalse(completed.toString().contains("private-retry-sentinel"));
    }

    @Test void twoIncompleteThinkingToolResponsesFailAfterExactlyOneRetry()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            int call=calls.incrementAndGet();
            respond(exchange,200,"""
                    {"id":"completion-incomplete-%d","choices":[{
                    "finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","tool_calls":[{"id":"discarded-%d","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}]}
                    """.formatted(call,call));
        });server.start();
        CapturingTelemetryStore telemetry=new CapturingTelemetryStore();
        TelemetryModelProvider provider=new TelemetryModelProvider(provider(),telemetry,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty);

        ModelResponse response;
        try(AutoCloseable ignored=com.paicli.context.ProviderRetryTestScope.open()){
            response=provider.chatForOperation("CHAT",
                    List.of(AgentMessage.userGoal("read README")),List.of(readFileDefinition()));
        }

        assertEquals(3,calls.get());
        assertEquals(ModelResponse.ProviderErrorKind
                .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY,
                response.providerErrorKind());
        assertEquals(3,telemetry.started.get());
        assertEquals(3,telemetry.finished.get());
    }

    @Test void nullAssistantContentOnThinkingToolCallIsNormalizedForContinuation()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            if(calls.incrementAndGet()==1)respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":null,"reasoning_content":"private-sentinel",
                    "tool_calls":[{"id":"call-no-content","type":"function",
                    "function":{"name":"list_files","arguments":"{}"}}]}}]}
                    """);
            else{continuation.set(request);respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"done"}}]}
                    """);}
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("list")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ResponseType.TOOL_CALL,first.type());
        assertEquals("",first.content());
        ModelResponse finalResponse=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("list"),action(first,"call-no-content"),
                AgentMessage.observationForToolCall("README.md","call-no-content")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ResponseType.FINAL,finalResponse.type());
        assertAssistantTurn(continuation.get(),"call-no-content","","private-sentinel");
        assertFalse(finalResponse.toString().contains("private-sentinel"));
    }

    @Test void whitespaceReasoningIsNotTrimmedAndIsSerializedUnchanged()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            if(calls.incrementAndGet()==1)respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"   ","tool_calls":[{
                    "id":"call-spaces","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}]}
                    """);
            else{continuation.set(request);respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"done"}}]}
                    """);}
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("read")),
                List.of(readFileDefinition()));

        assertEquals(ModelResponse.ResponseType.TOOL_CALL,first.type());
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation(
                "TOOL_CONTINUATION",List.of(AgentMessage.userGoal("read"),
                        action(first,"call-spaces"),
                        AgentMessage.observationForToolCall("evidence","call-spaces")),
                List.of(readFileDefinition())).type());
        assertEquals("   ",findAssistantTurn(continuation.get(),"call-spaces")
                .path("reasoning_content").textValue());
        assertEquals(2,calls.get());
    }

    @Test void nonTextReasoningTypesFailClosedWithoutCoercion()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            int call=calls.incrementAndGet();
            String value=switch(call){case 1->"7";case 2->"{}";default->"[]";};
            respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":%s,"tool_calls":[{
                    "id":"call-invalid-%d","type":"function",
                    "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}]}
                    """.formatted(value,call));
        });server.start();
        OpenAICompatibleProvider provider=provider();
        for(int index=0;index<3;index++){
            ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("read"+index)),
                    List.of(readFileDefinition()));
            assertEquals(ModelResponse.ProviderErrorKind
                    .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE,
                    response.providerErrorKind());
            assertEquals("REASONING_CONTENT_INVALID_TYPE_FOR_TOOL_CONTINUATION",
                    provider.takeObservation().errorCategory());
        }
        assertEquals(3,calls.get());
    }

    @Test void emptyFinalContentWithoutToolCallIsEmptyResponse()throws Exception{
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->respond(exchange,200,"""
                {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                "content":"","reasoning_content":"private-final-reasoning"}}]}
                """));server.start();
        ModelResponse response=provider().chat(List.of(AgentMessage.userGoal("finish")),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE,
                response.providerErrorKind());
        assertFalse(response.toString().contains("private-final-reasoning"));
    }

    @Test void missingToolCallIdAndFinishReasonConflictAreTyped()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            if(calls.incrementAndGet()==1)respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"private-one","tool_calls":[{"type":"function",
                    "function":{"name":"list_files","arguments":"{}"}}]}}]}
                    """);
            else respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"","reasoning_content":"private-two","tool_calls":[{"id":"call-conflict",
                    "type":"function","function":{"name":"list_files","arguments":"{}"}}]}}]}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider();
        assertEquals(ModelResponse.ProviderErrorKind.TOOL_CALL_ID_MISSING,
                provider.chat(List.of(AgentMessage.userGoal("one")),List.of(toolDefinition()))
                        .providerErrorKind());
        assertEquals(ModelResponse.ProviderErrorKind.FINISH_REASON_CONFLICT,
                provider.chat(List.of(AgentMessage.userGoal("two")),List.of(toolDefinition()))
                        .providerErrorKind());
    }

    @Test void multipleThinkingToolCallsReplayOneAssistantTurnAndAllResults()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<JsonNode> continuation=new AtomicReference<>();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            if(calls.incrementAndGet()==1)respond(exchange,200,"""
                {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                "content":"","reasoning_content":"private-multiple","tool_calls":[
                {"id":"call-a","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}},
                {"id":"call-b","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"notes.txt\\"}"}},
                {"id":"call-c","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"docs/guide.md\\"}"}}]}}]}
                """);
            else{continuation.set(request);respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"grounded final"}}]}
                    """);}
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("list")),
                List.of(readFileDefinition()));
        assertEquals(ModelResponse.ResponseType.TOOL_CALL,response.type());
        assertEquals(3,response.toolCalls().size());
        assertEquals(List.of("call-a","call-b","call-c"),response.toolCalls().stream()
                .map(com.paicli.model.ToolCall::providerCallId).toList());
        var callsJson=JSON.createArrayNode();
        for(var toolCall:response.toolCalls()){
            var root=JSON.createObjectNode();root.put("id",toolCall.providerCallId());
            root.put("type","function");var function=root.putObject("function");
            function.put("name",toolCall.name());
            function.put("arguments",JSON.writeValueAsString(toolCall.arguments()));
            callsJson.add(root);
        }
        List<AgentMessage> messages=new java.util.ArrayList<>();
        messages.add(AgentMessage.userGoal("list"));
        messages.add(AgentMessage.actionWithToolCall(response.content(),"call-a",
                callsJson.toString()));
        messages.add(AgentMessage.observationForToolCall("readme","call-a"));
        messages.add(AgentMessage.observationForToolCall("notes","call-b"));
        messages.add(AgentMessage.observationForToolCall("guide","call-c"));
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation(
                "TOOL_CONTINUATION",messages,List.of(readFileDefinition())).type());
        JsonNode requestMessages=continuation.get().path("messages");
        List<JsonNode> assistants=java.util.stream.StreamSupport.stream(
                requestMessages.spliterator(),false)
                .filter(message->message.path("tool_calls").size()==3).toList();
        assertEquals(1,assistants.size(),"batch must remain one assistant turn");
        assertEquals("private-multiple",assistants.getFirst()
                .path("reasoning_content").asText());
        assertEquals(List.of("call-a","call-b","call-c"),java.util.stream.StreamSupport
                .stream(requestMessages.spliterator(),false)
                .filter(message->"tool".equals(message.path("role").asText()))
                .map(message->message.path("tool_call_id").asText()).toList());
        assertFalse(response.toString().contains("private-multiple"));
    }

    @Test void continuationHttp400IsReasoningProtocolFailureWithoutBodyLeak()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            if(calls.incrementAndGet()==1)respond(exchange,200,toolResponse("call-400",
                    "inspect","private-400-sentinel","list_files"));
            else respond(exchange,400,"private server explanation");
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("list")),
                List.of(toolDefinition()));
        ModelResponse second=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("list"),action(first,"call-400"),
                AgentMessage.observationForToolCall("README.md","call-400")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_HTTP_400,
                second.providerErrorKind());
        assertFalse(second.toString().contains("private server explanation"));
        assertFalse(second.toString().contains("private-400-sentinel"));
    }

    @Test void incompleteBatchContinuationFailsBeforeSecondHttpRequest()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"private-incomplete","tool_calls":[
                    {"id":"call-one","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}},
                    {"id":"call-two","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"notes.txt\\"}"}}]}}]}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("read")),
                List.of(readFileDefinition()));
        assertEquals(2,first.toolCalls().size());
        ModelResponse rejected=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("read"),batchAction(first),
                AgentMessage.observationForToolCall("readme","call-one")),
                List.of(readFileDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind
                .TOOL_RESULT_MISSING_FOR_BATCH_CONTINUATION,rejected.providerErrorKind());
        assertEquals(1,calls.get(),"incomplete batch must fail before HTTP");
        assertFalse(rejected.toString().contains("private-incomplete"));
    }

    @Test void extraDuplicateAndReorderedBatchResultsFailBeforeHttp()throws Exception{
        assertMismatchedContinuationRejected(List.of("call-one","call-two","call-extra"));
        assertMismatchedContinuationRejected(List.of("call-one","call-one"));
        assertMismatchedContinuationRejected(List.of("call-two","call-one"));
    }

    @Test void resumedThinkingContinuationWithoutPrivateStateFailsBeforeHttp()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{calls.incrementAndGet();respond(exchange,200,"{} ");});
        server.start();
        String callJson="""
                {"id":"resume-call","type":"function",
                "function":{"name":"list_files","arguments":"{}"}}
                """;
        ModelResponse response=provider().chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("list"),
                AgentMessage.actionWithToolCall("list now","resume-call",callJson),
                AgentMessage.observationForToolCall("README.md","resume-call")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE,
                response.providerErrorKind());
        assertEquals(0,calls.get());
    }

    @Test void newConversationBoundaryClearsPriorPrivateContinuation()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            int call=calls.incrementAndGet();
            if(call==1)respond(exchange,200,toolResponse("old-call","inspect",
                    "old-private-reasoning","list_files"));
            else respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"{\\"acceptanceCriteria\\":[\\"done\\"]}"}}]}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse old=provider.chatForOperation("CHAT",List.of(
                AgentMessage.userGoal("old task")),List.of(toolDefinition()));
        assertEquals(ModelResponse.ResponseType.TOOL_CALL,old.type());
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation("PLAN",List.of(
                AgentMessage.userGoal("new task")),List.of()).type());
        ModelResponse rejected=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("old task"),action(old,"old-call"),
                AgentMessage.observationForToolCall("old result","old-call")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE,
                rejected.providerErrorKind());
        assertEquals(2,calls.get(),"stale continuation must be rejected before HTTP");
    }

    @Test void multipleThinkingToolTurnsReplayEachOwningReasoningExactly()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode body=JSON.readTree(exchange.getRequestBody());
            int call=calls.incrementAndGet();
            if(call==1){respond(exchange,200,toolResponse("call-1","",
                    "private-reasoning-one","list_files"));return;}
            assertAssistantTurn(body,"call-1","","private-reasoning-one");
            if(call==2){respond(exchange,200,toolResponse("call-2","",
                    "private-reasoning-two","list_files"));return;}
            assertAssistantTurn(body,"call-2","","private-reasoning-two");
            respond(exchange,200,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"grounded final"}}]}
                    """);
        });server.start();
        OpenAICompatibleProvider provider=provider();
        List<AgentMessage> messages=new java.util.ArrayList<>();
        messages.add(AgentMessage.userGoal("inspect"));
        ModelResponse first=provider.chatForOperation("CHAT",messages,List.of(toolDefinition()));
        messages.add(action(first,"call-1"));
        messages.add(AgentMessage.observationForToolCall("one","call-1"));
        ModelResponse second=provider.chatForOperation("TOOL_CONTINUATION",messages,
                List.of(toolDefinition()));
        messages.add(action(second,"call-2"));
        messages.add(AgentMessage.observationForToolCall("two","call-2"));
        ModelResponse third=provider.chatForOperation("TOOL_CONTINUATION",messages,
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ResponseType.FINAL,third.type());
        assertEquals(3,calls.get());
    }

    @Test void duplicateProviderToolCallIdFailsClosedWithoutOverwritingPrivateTurn()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            int call=calls.incrementAndGet();
            respond(exchange,200,toolResponse("duplicate-call","purpose-"+call,
                    "private-duplicate-"+call,"list_files"));
        });server.start();
        OpenAICompatibleProvider provider=provider();
        ModelResponse first=provider.chatForOperation("CHAT",List.of(
                AgentMessage.userGoal("inspect")),List.of(toolDefinition()));
        ModelResponse duplicate=provider.chatForOperation("TOOL_CONTINUATION",List.of(
                AgentMessage.userGoal("inspect"),action(first,"duplicate-call"),
                AgentMessage.observationForToolCall("README.md","duplicate-call")),
                List.of(toolDefinition()));
        assertEquals(ModelResponse.ProviderErrorKind.TOOL_CALL_ID_DUPLICATE,
                duplicate.providerErrorKind());
        assertEquals(2,calls.get());
        assertFalse(duplicate.toString().contains("private-duplicate"));
    }

    @Test void deepSeekHttpFailuresRemainTypedAndDoNotExposeBodies()throws Exception{
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            var messages=JSON.readTree(body).path("messages");
            String scenario=messages.get(messages.size()-1).path("content").asText();
            int status=switch(scenario){
                case "auth" -> 401;case "forbidden" -> 403;case "missing" -> 404;
                case "rate" -> 429;default -> 503;
            };
            respond(exchange,status,"secret provider body");
        });server.start();
        OpenAICompatibleProvider provider=provider();
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE,
                provider.chat(List.of(AgentMessage.userGoal("auth")),List.of()).providerErrorKind());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE,
                provider.chat(List.of(AgentMessage.userGoal("forbidden")),List.of())
                        .providerErrorKind());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_MODEL_UNAVAILABLE,
                provider.chat(List.of(AgentMessage.userGoal("missing")),List.of())
                        .providerErrorKind());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RATE_LIMITED,
                provider.chat(List.of(AgentMessage.userGoal("rate")),List.of()).providerErrorKind());
        ModelResponse serverFailure=provider.chat(List.of(AgentMessage.userGoal("server")),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_SERVER_FAILURE,
                serverFailure.providerErrorKind());
        assertFalse(serverFailure.toString().contains("secret provider body"));
    }

    @Test void deepSeekEmptyMalformedAndTimeoutResponsesAreTyped()throws Exception{
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(body.contains("slow")){
                try{Thread.sleep(1500);}catch(InterruptedException error){Thread.currentThread().interrupt();}
                respond(exchange,200,"{}");
            }else if(body.contains("empty"))respond(exchange,200,"");
            else respond(exchange,200,"not-json");
        });server.start();
        OpenAICompatibleProvider normal=provider();
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE,
                normal.chat(List.of(AgentMessage.userGoal("empty")),List.of()).providerErrorKind());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE,
                normal.chat(List.of(AgentMessage.userGoal("malformed")),List.of())
                        .providerErrorKind());
        Config config=deepSeekConfig();
        OpenAICompatibleProvider bounded=new OpenAICompatibleProvider(config.baseUrl(),
                "test-secret",config.model(),config.effectiveSystemPrompt(),1,
                OpenAICompatiblePreset.from(config),config.effectiveThinking(),
                config.effectiveReasoningEffort());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                bounded.chat(List.of(AgentMessage.userGoal("slow")),List.of())
                        .providerErrorKind());
    }

    @Test void deepSeekPresetDoesNotDuplicateV1WhenBaseUrlAlreadyContainsIt(){
        OpenAICompatiblePreset preset=OpenAICompatiblePreset.from(
                Config.defaults().withProvider("openai","deepseek-v4-flash",
                        "https://api.deepseek.com","DEEPSEEK_API_KEY")
                        .withProviderPreset("deepseek"));
        assertEquals("https://api.deepseek.com/chat/completions",
                preset.endpoint(java.net.URI.create("https://api.deepseek.com"),false).toString());
        assertEquals("https://api.deepseek.com/v1/chat/completions",
                preset.endpoint(java.net.URI.create("https://api.deepseek.com/v1"),false)
                        .toString());
        assertFalse(preset.endpoint(java.net.URI.create("https://api.deepseek.com/v1"),false)
                .toString().contains("/v1/v1/"));
    }

    private OpenAICompatibleProvider provider(){
        return provider(deepSeekConfig());
    }

    private Config deepSeekConfig(){
        return Config.defaults().withProvider("openai","deepseek-v4-flash",
                "http://localhost:"+server.getAddress().getPort(),"DEEPSEEK_API_KEY")
                .withProviderPreset("deepseek");
    }

    private OpenAICompatibleProvider provider(Config config){
        return new OpenAICompatibleProvider(config.baseUrl(),"test-secret",config.model(),
                config.effectiveSystemPrompt(),5,OpenAICompatiblePreset.from(config),
                config.effectiveThinking(),config.effectiveReasoningEffort());
    }

    private static ToolDefinition toolDefinition(){
        return new ToolDefinition("list_files","list",JSON.createObjectNode()
                .put("type","object"));
    }

    private static ToolDefinition readFileDefinition(){
        return new ToolDefinition("read_file","read",JSON.createObjectNode()
                .put("type","object"));
    }

    private static AgentMessage action(ModelResponse response,String callId)throws Exception{
        var function=JSON.createObjectNode();
        function.put("name",response.toolCall().name());
        function.put("arguments",JSON.writeValueAsString(response.toolCall().arguments()));
        var root=JSON.createObjectNode();root.put("id",callId);root.put("type","function");
        root.set("function",function);
        return AgentMessage.actionWithToolCall(response.content(),callId,root.toString());
    }

    private static AgentMessage batchAction(ModelResponse response)throws Exception{
        var array=JSON.createArrayNode();
        for(var call:response.toolCalls()){
            var root=array.addObject();root.put("id",call.providerCallId());
            root.put("type","function");var function=root.putObject("function");
            function.put("name",call.name());
            function.put("arguments",JSON.writeValueAsString(call.arguments()));
        }
        return AgentMessage.actionWithToolCall(response.content(),
                response.toolCalls().getFirst().providerCallId(),array.toString());
    }

    private void assertMismatchedContinuationRejected(List<String> observationIds)
            throws Exception{
        AtomicInteger calls=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            calls.incrementAndGet();respond(exchange,200,"""
                    {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","reasoning_content":"private-mismatch","tool_calls":[
                    {"id":"call-one","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}},
                    {"id":"call-two","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"notes.txt\\"}"}}]}}]}
                    """);
        });server.start();
        try{
            OpenAICompatibleProvider provider=provider();
            ModelResponse first=provider.chat(List.of(AgentMessage.userGoal("read")),
                    List.of(readFileDefinition()));
            List<AgentMessage> messages=new java.util.ArrayList<>();
            messages.add(AgentMessage.userGoal("read"));messages.add(batchAction(first));
            for(String id:observationIds)
                messages.add(AgentMessage.observationForToolCall("bounded",id));
            ModelResponse rejected=provider.chatForOperation("TOOL_CONTINUATION",messages,
                    List.of(readFileDefinition()));
            assertEquals(ModelResponse.ProviderErrorKind
                    .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH,rejected.providerErrorKind());
            ProviderTransportEvidence evidence = provider.takeTransportEvidence();
            assertEquals(ProviderTransportEvidence.TransportState.PRE_TRANSPORT_REJECTED,
                    evidence.transportState());
            assertEquals(ProviderTransportEvidence.UsageProvenance
                            .NOT_APPLICABLE_NO_REQUEST_SENT,
                    evidence.usageProvenance());
            assertEquals("TOOL_RESULT_BATCH_CONTINUATION_MISMATCH",
                    evidence.unavailableReason());
            assertNull(evidence.httpStatus());
            assertEquals(1,calls.get());
            assertFalse(rejected.toString().contains("private-mismatch"));
        }finally{server.stop(0);server=null;}
    }

    private static String toolResponse(String id,String content,String reasoning,String name){
        return "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{"+
                "\"role\":\"assistant\",\"content\":"+quote(content)+","+
                "\"reasoning_content\":"+quote(reasoning)+",\"tool_calls\":[{"+
                "\"id\":"+quote(id)+",\"type\":\"function\",\"function\":{"+
                "\"name\":"+quote(name)+",\"arguments\":\"{}\"}}]}}]}";
    }

    private static String quote(String value){
        try{return JSON.writeValueAsString(value);}catch(Exception error){throw new AssertionError(error);}
    }

    private static void assertAssistantTurn(JsonNode request,String id,String content,
            String reasoning){
        JsonNode turn=findAssistantTurn(request,id);
        assertEquals(content,turn.path("content").asText());
        assertEquals(reasoning,turn.path("reasoning_content").asText());
    }

    private static JsonNode findAssistantTurn(JsonNode request,String id){
        return java.util.stream.StreamSupport.stream(
                request.path("messages").spliterator(),false)
                .filter(message->id.equals(message.path("tool_calls").path(0)
                        .path("id").asText())).findFirst().orElseThrow();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,int status,String body)
            throws java.io.IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }

    private static final class CapturingTelemetryStore extends ProviderCallTelemetryStore{
        private final AtomicInteger started=new AtomicInteger();
        private final AtomicInteger finished=new AtomicInteger();
        private CapturingTelemetryStore(){super(Path.of("."));}
        @Override public boolean recordStarted(CallStart start){started.incrementAndGet();return true;}
        @Override public boolean finalizeCall(CallFinish finish){finished.incrementAndGet();return true;}
    }
}
