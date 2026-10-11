package com.paicli.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OllamaProvider using a local mock HTTP server (JDK built-in).
 * Does NOT require a real Ollama instance.
 */
class OllamaProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer mockServer;
    private static int port;
    private static AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private static AtomicReference<String> lastShowRequestBody = new AtomicReference<>();

    @BeforeAll
    static void startMockServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockServer.getAddress().getPort();

        mockServer.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            lastRequestBody.set(body);

            if (body.contains("provider http failure")) {
                byte[] rejected = "secret-bearing-raw-body".getBytes();
                exchange.sendResponseHeaders(503, rejected.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(rejected);
                }
                return;
            }
            if (body.contains("provider parse failure")) {
                byte[] malformed = "{}".getBytes();
                exchange.sendResponseHeaders(200, malformed.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(malformed);
                }
                return;
            }
            if (body.contains("provider empty response")) {
                exchange.sendResponseHeaders(200, 0);
                exchange.close();
                return;
            }
            if (body.contains("model unavailable")) {
                byte[] rejected = "model is not installed".getBytes();
                exchange.sendResponseHeaders(404, rejected.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(rejected);
                }
                return;
            }
            if (body.contains("provider timeout")) {
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }

            // Parse the request to determine response
            ObjectNode response = MAPPER.createObjectNode();
            response.put("prompt_eval_count", 11);
            response.put("eval_count", 5);
            response.put("done_reason", "stop");
            ObjectNode message = response.putObject("message");
            message.put("role", "assistant");

            ObjectNode requestJson = (ObjectNode) MAPPER.readTree(body);
            boolean hasTools = requestJson.has("tools") && requestJson.get("tools").isArray()
                    && requestJson.get("tools").size() > 0;

            // If tools provided and first user message contains "list", return tool call
            boolean shouldToolCall = false;
            boolean shouldMalformedToolCall = false;
            boolean shouldMultipleToolCall = false;
            if (hasTools) {
                ArrayNode msgs = (ArrayNode) requestJson.get("messages");
                for (var msg : msgs) {
                    if ("user".equals(msg.path("role").asText())) {
                        String content = msg.path("content").asText().toLowerCase();
                        if (content.contains("malformed")) {
                            shouldMalformedToolCall = true;
                            break;
                        }
                        if (content.contains("multiple")) {
                            shouldMultipleToolCall = true;
                            break;
                        }
                        if (content.contains("list")) {
                            shouldToolCall = true;
                            break;
                        }
                    }
                }
            }

            if (shouldMalformedToolCall) {
                message.put("content", "This content must not become a final answer");
                ArrayNode toolCalls = message.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", "call_malformed");
                ObjectNode func = tc.putObject("function");
                func.put("name", "list_files");
                func.put("arguments", "{not-json");
            } else if (shouldMultipleToolCall) {
                message.put("content", "secret-response-body");
                ArrayNode toolCalls = message.putArray("tool_calls");
                for (int index = 0; index < 2; index++) {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", "call_" + index);
                    ObjectNode func = tc.putObject("function");
                    func.put("name", "list_files");
                    func.putObject("arguments").put("path", ".");
                }
            } else if (shouldToolCall) {
                message.put("content", "I'll list the files");
                ArrayNode toolCalls = message.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", "call_test123");
                ObjectNode func = tc.putObject("function");
                func.put("name", "list_files");
                func.putObject("arguments").put("path", ".");
            } else {
                message.put("content", "This is the final answer from mock Ollama.");
            }

            byte[] responseBytes = MAPPER.writeValueAsBytes(response);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        mockServer.createContext("/api/show", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            lastShowRequestBody.set(body);
            byte[] responseBytes;
            if (body.contains("oversized-model")) {
                responseBytes = "x".repeat(65_537).getBytes();
            } else if (body.contains("unknown-model")) {
                responseBytes = "{\"model_info\":{}}".getBytes();
            } else {
                responseBytes = """
                        {"details":{"family":"qwen3"},"model_info":{
                          "general.architecture":"qwen3",
                          "qwen3.context_length":40960
                        }}
                        """.getBytes();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        mockServer.start();
    }

    @AfterAll
    static void stopMockServer() {
        if (mockServer != null) mockServer.stop(0);
    }

    private OllamaProvider createProvider() {
        return new OllamaProvider(
                "http://localhost:" + port,
                "test-model",
                "You are a test agent.",
                30
        );
    }

    @Test
    void finalAnswerWhenNoTools() {
        var provider = createProvider();
        var messages = List.of(AgentMessage.userGoal("hello"));

        ModelResponse response = provider.chat(messages, List.of());

        assertEquals(ModelResponse.ResponseType.FINAL, response.type());
        assertTrue(response.content().contains("final answer from mock Ollama"));
    }

    @Test
    void readsProviderReportedContextLimitForExactModelIdentity() {
        assertEquals(40960, createProvider().reportedContextWindowTokens());
        assertEquals("test-model",
                assertDoesNotThrow(() -> MAPPER.readTree(lastShowRequestBody.get()))
                        .path("model").asText());
    }

    @Test
    void malformedOrMissingContextMetadataRemainsUnknown() {
        OllamaProvider provider = new OllamaProvider(
                "http://localhost:" + port, "unknown-model", "system", 30);
        assertNull(provider.reportedContextWindowTokens());
        OllamaProvider oversized = new OllamaProvider(
                "http://localhost:" + port, "oversized-model", "system", 30);
        assertNull(oversized.reportedContextWindowTokens());
    }

    @Test
    void exposesAllowlistedUsageWithoutRawResponse() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("usage")), List.of());
        var observation = provider.takeObservation();
        assertEquals(11, observation.inputTokens());
        assertEquals(5, observation.outputTokens());
        assertEquals(16, observation.totalTokens());
        assertEquals("stop", observation.finishReason());
        assertFalse(observation.toString().contains("final answer from mock Ollama"));
    }

    @Test
    void toolCallWhenToolsProvided() {
        var provider = createProvider();
        var messages = List.of(AgentMessage.userGoal("list all files"));
        var tools = List.of(
                new ToolDefinition("list_files", "List files", MAPPER.createObjectNode())
        );

        ModelResponse response = provider.chat(messages, tools);

        assertEquals(ModelResponse.ResponseType.TOOL_CALL, response.type());
        assertNotNull(response.toolCall());
        assertEquals("list_files", response.toolCall().name());
    }


    @Test
    void malformedToolCallReturnsModelErrorInsteadOfFinalContent() {
        var provider = createProvider();
        var messages = List.of(AgentMessage.userGoal("malformed tool call"));
        var tools = List.of(
                new ToolDefinition("list_files", "List files", MAPPER.createObjectNode())
        );

        ModelResponse response = provider.chat(messages, tools);

        assertEquals(ModelResponse.ResponseType.PROTOCOL_ERROR, response.type());
        assertNull(response.content());
        assertEquals("INVALID_TOOL_CALL_RESPONSE",
                response.providerEnvelope().diagnostic().code());
    }

    @Test
    void multipleToolCallsAreAdmittedAsOneOrderedBatch() {
        ModelResponse response = createProvider().chat(
                List.of(AgentMessage.userGoal("multiple tool calls")),
                List.of(new ToolDefinition("list_files", "List files",
                        MAPPER.createObjectNode())));
        assertEquals(ModelResponse.ResponseType.TOOL_CALL, response.type());
        assertEquals(2, response.toolCalls().size());
        assertEquals(List.of("call_0", "call_1"), response.toolCalls().stream()
                .map(ToolCall::providerCallId).toList());
        assertNull(response.providerEnvelope().diagnostic().code());
    }

    @Test
    void multipleToolContinuationSerializesOneAssistantTurnAndOrderedResults() throws Exception {
        String toolCalls = """
                [{"id":"call_0","type":"function","function":{"name":"list_files","arguments":{"path":"."}}},
                 {"id":"call_1","type":"function","function":{"name":"read_file","arguments":{"path":"README.md"}}}]
                """;
        createProvider().chat(List.of(
                AgentMessage.userGoal("continue a completed batch"),
                AgentMessage.actionWithToolCall("batch", "call_0", toolCalls),
                AgentMessage.observationForToolCall("listed", "call_0"),
                AgentMessage.observationForToolCall("read", "call_1")), List.of());

        ArrayNode messages = (ArrayNode) MAPPER.readTree(lastRequestBody.get()).path("messages");
        List<com.fasterxml.jackson.databind.JsonNode> assistant = new java.util.ArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> toolResults = new java.util.ArrayList<>();
        messages.forEach(message -> {
            if ("assistant".equals(message.path("role").asText())
                    && message.has("tool_calls")) assistant.add(message);
            if ("tool".equals(message.path("role").asText())) toolResults.add(message);
        });
        assertEquals(1, assistant.size());
        assertEquals(2, assistant.getFirst().path("tool_calls").size());
        assertEquals(List.of("call_0", "call_1"), toolResults.stream()
                .map(message -> message.path("tool_call_id").asText()).toList());
    }
    @Test
    void requestIncludesSystemPrompt() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String body = lastRequestBody.get();
        assertNotNull(body);
        assertTrue(body.contains("You are a test agent."));
    }

    @Test
    void requestIncludesModelName() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String body = lastRequestBody.get();
        assertTrue(body.contains("test-model"));
    }

    @Test
    void requestIncludesTools() {
        var provider = createProvider();
        var tools = List.of(
                new ToolDefinition("list_files", "List files", MAPPER.createObjectNode())
        );
        provider.chat(List.of(AgentMessage.userGoal("test")), tools);

        String body = lastRequestBody.get();
        assertTrue(body.contains("list_files"));
        assertTrue(body.contains("function"));
    }

    @Test
    void connectionErrorReturnsTypedProviderFailure() {
        var provider = new OllamaProvider(
                "http://localhost:19999", // unlikely to be running
                "test-model", "system", 5
        );

        ModelResponse response = provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        assertEquals(ModelResponse.ResponseType.PROVIDER_ERROR, response.type());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_CONNECTION_FAILURE,
                response.providerErrorKind());
        assertNull(response.content());
    }

    @Test
    void httpAndParseFailuresAreTypedAndDoNotRetainRawBody() {
        ModelResponse http = createProvider().chat(
                List.of(AgentMessage.userGoal("provider http failure")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_HTTP_FAILURE,
                http.providerErrorKind());
        assertFalse(http.toString().contains("secret-bearing-raw-body"));

        ModelResponse parse = createProvider().chat(
                List.of(AgentMessage.userGoal("provider parse failure")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE,
                parse.providerErrorKind());
    }

    @Test
    void modelUnavailableEmptyTimeoutAndSerializationAreDistinct() {
        ModelResponse unavailable = createProvider().chat(
                List.of(AgentMessage.userGoal("model unavailable")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_MODEL_UNAVAILABLE,
                unavailable.providerErrorKind());

        ModelResponse empty = createProvider().chat(
                List.of(AgentMessage.userGoal("provider empty response")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE,
                empty.providerErrorKind());

        ModelResponse timeout = new OllamaProvider(
                "http://localhost:" + port, "test-model", "system",
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(1)).build(),
                java.time.Duration.ofMillis(100))
                .chat(List.of(AgentMessage.userGoal("provider timeout")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                timeout.providerErrorKind());

        ModelResponse serialization = createProvider().chat(
                java.util.Arrays.asList((AgentMessage) null), List.of());
        assertEquals(
                ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_SERIALIZATION_FAILURE,
                serialization.providerErrorKind());
    }

    @Test
    void messageHistoryPreserved() {
        var provider = createProvider();
        var messages = List.of(
                AgentMessage.userGoal("list files"),
                AgentMessage.action("list_files({path=.})"),
                AgentMessage.observation("file1.txt" + System.lineSeparator() + "file2.txt"),
                AgentMessage.userGoal("now summarize")
        );

        provider.chat(messages, List.of());

        String body = lastRequestBody.get();
        assertTrue(body.contains("file1.txt"));
        assertTrue(body.contains("now summarize"));
    }
}
