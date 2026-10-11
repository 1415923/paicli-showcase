package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.provider.OpenAICompatiblePreset;
import com.paicli.provider.OpenAICompatibleProvider;
import com.paicli.safety.ApprovalPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SimpleToolLoopBaselineTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path workspace;

    @Test
    void usesProductionDeepSeekAdapterAndPreservesPrivateContinuation() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "bounded fixture", StandardCharsets.UTF_8);
        AtomicReference<JsonNode> continuation = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            boolean hasToolResult = false;
            for (JsonNode message : body.path("messages")) {
                if ("tool".equals(message.path("role").asText())) hasToolResult = true;
            }
            if (!hasToolResult) {
                respond(exchange, 200, """
                        {"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant",
                        "content":"","reasoning_content":"private-baseline-sentinel",
                        "tool_calls":[{"id":"call-shared-1","type":"function",
                        "function":{"name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}]}}],
                        "usage":{"prompt_tokens":8,"completion_tokens":3,"total_tokens":11}}
                        """);
            } else {
                continuation.set(body);
                respond(exchange, 200, """
                        {"choices":[{"finish_reason":"stop","message":{"role":"assistant",
                        "content":"README.md is present."}}],
                        "usage":{"prompt_tokens":12,"completion_tokens":5,"total_tokens":17}}
                        """);
            }
        });
        server.start();
        try {
            Config config = Config.defaults()
                    .withProvider("openai", "deepseek-v4-flash",
                            "http://localhost:" + server.getAddress().getPort(), "DEEPSEEK_API_KEY")
                    .withProviderPreset("deepseek")
                    .withThinking("enabled", "max");
            OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                    config.baseUrl(), "loopback-placeholder", config.model(),
                    config.effectiveSystemPrompt(), 5, OpenAICompatiblePreset.from(config),
                    config.effectiveThinking(), config.effectiveReasoningEffort());
            SimpleToolLoopBaseline baseline = new SimpleToolLoopBaseline(
                    provider, BenchmarkToolRegistryFactory.create(workspace),
                    new ApprovalPolicy(() -> Config.MODE_READ_ONLY, "benchmark-task"),
                    new PredeclaredApprovalControl((iteration, call) -> false), 6);

            var result = baseline.run("Read README.md and report whether it is present.");

            assertTrue(result.agentResult().completed());
            assertEquals("README.md is present.", result.agentResult().answer());
            assertEquals(OpenAICompatibleProvider.class.getName(), baseline.providerAdapterId());
            assertEquals(2, result.providerTurns());
            assertEquals(1, result.toolCalls());
            assertEquals(java.util.List.of("CHAT", "TOOL_CONTINUATION"), result.providerOperations());
            assertEquals(2, result.providerObservations().size());

            JsonNode replayedAssistant = null;
            for (JsonNode message : continuation.get().path("messages")) {
                if ("assistant".equals(message.path("role").asText())
                        && message.has("tool_calls")) replayedAssistant = message;
            }
            assertNotNull(replayedAssistant);
            assertEquals("private-baseline-sentinel",
                    replayedAssistant.path("reasoning_content").asText());
            assertEquals("call-shared-1",
                    replayedAssistant.path("tool_calls").get(0).path("id").asText());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
