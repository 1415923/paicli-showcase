package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentResult;
import com.paicli.config.Config;
import com.paicli.safety.SafetyPolicy;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolResult;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekMultiToolBatchIntegrationTest {
    private static final ObjectMapper JSON=new ObjectMapper();

    @TempDir Path workspace;

    @Test void listThenThreeReadCallsContinueAsOneThinkingBatchToGroundedFinal()
            throws Exception {
        Files.writeString(workspace.resolve("README.md"),"README workspace purpose");
        Files.writeString(workspace.resolve("notes.txt"),"Notes fixture purpose");
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/guide.md"),"Guide fixture purpose");
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/state.db"),"private-control-state");

        AtomicInteger calls=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            int number=calls.incrementAndGet();
            if(number==1){
                respond(exchange,"""
                        {"choices":[{"finish_reason":"tool_calls","message":{
                        "role":"assistant","content":"","reasoning_content":"private-list",
                        "tool_calls":[{"id":"call-list","type":"function","function":{
                        "name":"list_files","arguments":"{\\"path\\":\\".\\"}"}}]}}]}
                        """);return;
            }
            List<JsonNode> toolMessages=java.util.stream.StreamSupport.stream(
                    request.path("messages").spliterator(),false)
                    .filter(message->"tool".equals(message.path("role").asText())).toList();
            if(number==2){
                assertEquals(1,toolMessages.size());
                assertEquals("call-list",toolMessages.getFirst().path("tool_call_id").asText());
                String listFact=toolMessages.getFirst().path("content").asText();
                assertTrue(listFact.contains("\"excludedControls\":[\".paicli/\"]"));
                assertTrue(listFact.contains("\"trustClass\":\"RUNTIME_VERIFIED\""));
                assertFalse(listFact.contains("state.db"));
                respond(exchange,"""
                        {"choices":[{"finish_reason":"tool_calls","message":{
                        "role":"assistant","content":"","reasoning_content":"",
                        "tool_calls":[
                        {"id":"call-readme","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}},
                        {"id":"call-notes","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"notes.txt\\"}"}},
                        {"id":"call-guide","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"docs/guide.md\\"}"}}]}}]}
                        """);return;
            }
            assertEquals(List.of("call-list","call-readme","call-notes","call-guide"),
                    toolMessages.stream().map(message->message.path("tool_call_id").asText())
                            .toList());
            List<JsonNode> batchAssistant=java.util.stream.StreamSupport.stream(
                    request.path("messages").spliterator(),false)
                    .filter(message->message.path("tool_calls").size()==3).toList();
            assertEquals(1,batchAssistant.size());
            assertTrue(batchAssistant.getFirst().has("reasoning_content"));
            assertEquals("",batchAssistant.getFirst()
                    .path("reasoning_content").textValue());
            respond(exchange,"""
                    {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                    "content":"README.md explains the workspace; notes.txt contains fixture notes; docs/guide.md is the test guide. The .paicli control directory exists but was excluded. No files were modified."}}]}
                    """);
        });server.start();
        try{
            Config config=Config.defaults().withProvider("openai","deepseek-v4-flash",
                    "http://localhost:"+server.getAddress().getPort(),"DEEPSEEK_API_KEY")
                    .withProviderPreset("deepseek");
            OpenAICompatibleProvider provider=new OpenAICompatibleProvider(config.baseUrl(),
                    "test-secret",config.model(),config.effectiveSystemPrompt(),5,
                    OpenAICompatiblePreset.from(config),config.effectiveThinking(),
                    config.effectiveReasoningEffort());
            ToolRegistry registry=new ToolRegistry();SafetyPolicy policy=new SafetyPolicy(workspace);
            registry.register(new ListFilesTool(policy));registry.register(new ReadFileTool(policy));

            AgentResult result=new AgentLoop(provider,registry,8).run(
                    "List user files and ignore .paicli; explain each file without changes.");

            assertTrue(result.completed());
            assertEquals(3,calls.get());
            assertTrue(result.answer().contains("README.md"));
            assertTrue(result.answer().contains("notes.txt"));
            assertTrue(result.answer().contains("docs/guide.md"));
            assertTrue(result.answer().contains(".paicli control directory exists but was excluded"));
            assertFalse(result.toString().contains("private-list"));
            assertFalse(result.toString().contains("private-batch"));
        }finally{server.stop(0);}
    }

    @Test void effectfulBatchIsProtocolValidPreservesReasoningAndExecutesNothing()
            throws Exception {
        AtomicInteger calls=new AtomicInteger();
        AtomicInteger executions=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            JsonNode request=JSON.readTree(exchange.getRequestBody());
            if(calls.incrementAndGet()==1){
                respond(exchange,"""
                        {"id":"response-effectful","usage":{"prompt_tokens":11,
                        "completion_tokens":7,"total_tokens":18},"choices":[{
                        "finish_reason":"tool_calls","message":{"role":"assistant",
                        "content":"","reasoning_content":"private-effectful-sentinel",
                        "tool_calls":[
                        {"id":"call-write-a","type":"function","function":{
                        "name":"write_file","arguments":"{\\"path\\":\\"a.txt\\",\\"content\\":\\"a\\"}"}},
                        {"id":"call-write-b","type":"function","function":{
                        "name":"write_file","arguments":"{\\"path\\":\\"b.txt\\",\\"content\\":\\"b\\"}"}}]}}]}
                        """);return;
            }
            List<JsonNode> assistant=java.util.stream.StreamSupport.stream(
                    request.path("messages").spliterator(),false)
                    .filter(message->message.path("tool_calls").size()==2).toList();
            assertEquals(1,assistant.size());
            assertEquals("private-effectful-sentinel",
                    assistant.getFirst().path("reasoning_content").asText());
            List<JsonNode> results=java.util.stream.StreamSupport.stream(
                    request.path("messages").spliterator(),false)
                    .filter(message->"tool".equals(message.path("role").asText())).toList();
            assertEquals(List.of("call-write-a","call-write-b"),results.stream()
                    .map(message->message.path("tool_call_id").asText()).toList());
            assertTrue(results.stream().allMatch(message->message.path("content").asText()
                    .contains("EFFECTFUL_BATCH_REQUIRES_SERIALIZATION")));
            respond(exchange,"""
                    {"id":"response-final","usage":{"prompt_tokens":17,
                    "completion_tokens":3,"total_tokens":20},"choices":[{
                    "finish_reason":"stop","message":{"role":"assistant",
                    "content":"serialized without execution"}}]}
                    """);
        });server.start();
        try{
            Config config=Config.defaults().withProvider("openai","deepseek-v4-flash",
                    "http://localhost:"+server.getAddress().getPort(),"DEEPSEEK_API_KEY")
                    .withProviderPreset("deepseek");
            OpenAICompatibleProvider provider=new OpenAICompatibleProvider(config.baseUrl(),
                    "test-secret",config.model(),config.effectiveSystemPrompt(),5,
                    OpenAICompatiblePreset.from(config),config.effectiveThinking(),
                    config.effectiveReasoningEffort());
            ToolRegistry registry=new ToolRegistry();
            registry.register(new Tool(){
                public String name(){return "write_file";}
                public String description(){return "test write";}
                public ToolDefinition toDefinition(){return new ToolDefinition(name(),description(),
                        JSON.createObjectNode());}
                public ToolResult execute(ToolCall call){executions.incrementAndGet();
                    return ToolResult.success("unexpected");}
            });

            AgentResult result=new AgentLoop(provider,registry,4).run("write two files safely");

            assertTrue(result.completed());
            assertEquals(2,calls.get());
            assertEquals(0,executions.get());
            assertFalse(result.toString().contains("private-effectful-sentinel"));
        }finally{server.stop(0);}
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,String body)
            throws java.io.IOException{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type","application/json");
        exchange.sendResponseHeaders(200,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
}
