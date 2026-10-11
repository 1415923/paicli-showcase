package com.paicli.diagnostic;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paicli.interactive.runtime.InteractiveCodeIntelligenceStage;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.OpenAICompatiblePreset;
import com.paicli.provider.OpenAICompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageTraceTest {
    @TempDir
    Path temp;

    @AfterEach
    void closeTrace() throws Exception {
        StageTrace.closeForTests();
    }

    @Test
    void criticalEventIsReadableBeforeTraceCloseAndArgumentsRemainHashed()
            throws Exception {
        StageTrace.resetForTests(temp);
        String sensitiveCommand = "powershell -Command secret-value";

        StageTrace.event("PROVIDER_CALL_START", true,
                "executable_basename", "powershell.exe",
                "argument_hash", StageTrace.hashArguments(List.of(sensitiveCommand)));

        String trace = Files.readString(
                temp.resolve("diagnostics").resolve(StageTrace.FILE_NAME));
        assertTrue(trace.contains("stage=PROVIDER_CALL_START"));
        assertTrue(trace.contains("executable_basename=powershell.exe"));
        assertFalse(trace.contains(sensitiveCommand));
        assertFalse(trace.contains("secret-value"));
    }

    @Test
    void controlCharactersCannotCreateForgedTraceRecords() throws Exception {
        StageTrace.resetForTests(temp);

        StageTrace.event("WORKSPACE_SELECTED", true,
                "selected", "C:\\workspace\n|stage=FORGED");

        List<String> lines = Files.readAllLines(
                temp.resolve("diagnostics").resolve(StageTrace.FILE_NAME));
        assertTrue(lines.size() == 1);
        assertTrue(lines.getFirst().contains("stage=WORKSPACE_SELECTED"));
        assertFalse(lines.getFirst().contains("\n"));
        assertFalse(lines.getFirst().contains("|stage=FORGED"));
    }

    @Test
    void zeroCodeCandidatesEmitSkipWithoutStartingWorkspaceScan() throws Exception {
        StageTrace.resetForTests(temp);
        Path workspace = Files.createDirectories(temp.resolve("普通工作区"));

        var facts = new InteractiveCodeIntelligenceStage().inspect(
                workspace, "列出当前目录中的文件并简要说明用途", List.of(), null);

        String trace = Files.readString(
                temp.resolve("diagnostics").resolve(StageTrace.FILE_NAME));
        assertTrue(trace.contains("stage=CODE_CANDIDATE_EXTRACTION_END"));
        assertTrue(trace.contains("candidate_count=0"));
        assertTrue(trace.contains("stage=CODE_SCAN_SKIPPED"));
        assertTrue(trace.contains("reason=NO_CODE_CANDIDATES_OR_FILE_REFERENCES"));
        assertFalse(trace.contains("stage=CODE_SCAN_START"));
        assertTrue(facts.indexedJavaFiles() == 0);
        assertTrue(facts.contextItemCount() == 0);
    }

    @Test
    void providerTraceDistinguishesPresentEmptyReasoningWithoutLeakingPayload()
            throws Exception {
        StageTrace.resetForTests(temp);
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/chat/completions",exchange->{
            byte[] body="""
                    {"choices":[{"finish_reason":"tool_calls","message":{
                    "role":"assistant","content":"","reasoning_content":"",
                    "tool_calls":[{"id":"call-trace","type":"function","function":{
                    "name":"read_file","arguments":"{\\"path\\":\\"sensitive-name.md\\"}"}}]}}],
                    "usage":{"prompt_tokens":9,"completion_tokens":0,"total_tokens":9,
                    "completion_tokens_details":{"reasoning_tokens":0}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        try{
            Config config=Config.defaults().withProvider("openai","deepseek-v4-flash",
                    "http://localhost:"+server.getAddress().getPort(),"DEEPSEEK_API_KEY")
                    .withProviderPreset("deepseek");
            OpenAICompatibleProvider provider=new OpenAICompatibleProvider(config.baseUrl(),
                    "test-secret",config.model(),config.effectiveSystemPrompt(),5,
                    OpenAICompatiblePreset.from(config),config.effectiveThinking(),
                    config.effectiveReasoningEffort());
            ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("bounded")),
                    List.of(new ToolDefinition("read_file","read",
                            new ObjectMapper().createObjectNode().put("type","object"))));
            assertTrue(response.type()==ModelResponse.ResponseType.TOOL_CALL);

            String trace=Files.readString(temp.resolve("diagnostics")
                    .resolve(StageTrace.FILE_NAME));
            assertTrue(trace.contains("reasoning_state=PRESENT_EMPTY"));
            assertTrue(trace.contains("reasoning_present=true"));
            assertTrue(trace.contains("reasoning_empty=true"));
            assertTrue(trace.contains("reasoning_length=0"));
            assertTrue(trace.contains("usage_reasoning_tokens=0"));
            assertFalse(trace.contains("sensitive-name.md"));
            assertFalse(trace.contains("test-secret"));
        }finally{server.stop(0);}
    }
}
