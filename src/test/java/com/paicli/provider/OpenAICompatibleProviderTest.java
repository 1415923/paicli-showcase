package com.paicli.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.*;
import com.paicli.credential.ProviderCredentialSource;
import com.paicli.provider.telemetry.ProviderTransportEvidence;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests OpenAICompatibleProvider using a local mock HTTP server.
 * Does NOT require a real API key or network access.
 */
class OpenAICompatibleProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer mockServer;
    private static int port;
    private static AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private static AtomicReference<String> lastAuthHeader = new AtomicReference<>();

    @BeforeAll
    static void startMockServer() throws Exception {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = mockServer.getAddress().getPort();

        mockServer.createContext("/v1/chat/completions", exchange -> {
            lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));

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
            boolean omitUsage = body.contains("provider missing usage");

            ObjectNode requestJson = (ObjectNode) MAPPER.readTree(body);

            ObjectNode response = MAPPER.createObjectNode();
            response.put("id", "chatcmpl-test");
            response.put("object", "chat.completion");
            if (!omitUsage) {
                ObjectNode usage = response.putObject("usage");
                usage.put("prompt_tokens", 13);
                usage.put("completion_tokens", 6);
                usage.put("total_tokens", 19);
                usage.putObject("prompt_tokens_details").put("cached_tokens", 2);
                usage.putObject("completion_tokens_details").put("reasoning_tokens", 1);
            }
            ArrayNode choices = response.putArray("choices");

            ObjectNode choice = choices.addObject();
            choice.put("index", 0);
            choice.put("finish_reason", "stop");

            ObjectNode message = choice.putObject("message");
            message.put("role", "assistant");

            boolean shouldToolCall = false;
            boolean shouldMalformedToolCall = false;
            boolean shouldMultipleToolCall = false;
            if (requestJson.has("tools") && requestJson.get("tools").isArray()
                    && requestJson.get("tools").size() > 0) {
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
                tc.put("type", "function");
                ObjectNode func = tc.putObject("function");
                func.put("name", "list_files");
                func.put("arguments", "{not-json");
            } else if (shouldMultipleToolCall) {
                message.put("content", "secret-response-body");
                ArrayNode toolCalls = message.putArray("tool_calls");
                for (int index = 0; index < 2; index++) {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", "call_" + index);
                    tc.put("type", "function");
                    ObjectNode func = tc.putObject("function");
                    func.put("name", "list_files");
                    func.put("arguments", "{\"path\":\".\"}");
                }
            } else if (shouldToolCall) {
                message.putNull("content");
                ArrayNode toolCalls = message.putArray("tool_calls");
                ObjectNode tc = toolCalls.addObject();
                tc.put("id", "call_test123");
                tc.put("type", "function");
                ObjectNode func = tc.putObject("function");
                func.put("name", "list_files");
                func.put("arguments", "{\"path\": \".\"}");
            } else {
                message.put("content", "This is the final answer from mock OpenAI API.");
                if(body.contains("final channels fixture")) {
                    message.put("reasoning_content", "PRIVATE_REASONING_SENTINEL");
                    message.put("content", "Result: the source contains the literal 'Let me think'.\n```java\nString label = \"analysis\";\n```");
                }
            }

            byte[] responseBytes = MAPPER.writeValueAsBytes(response);
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

    private OpenAICompatibleProvider createProvider() {
        return new OpenAICompatibleProvider(
                "http://localhost:" + port,
                "test-api-key-12345",
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
        assertTrue(response.content().contains("final answer from mock OpenAI"));
    }

    @Test
    void exposesCompatibleAllowlistedUsageAndFinishReason() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("usage")), List.of());
        var observation = provider.takeObservation();
        assertEquals(13, observation.inputTokens());
        assertEquals(6, observation.outputTokens());
        assertEquals(19, observation.totalTokens());
        assertEquals(2, observation.cacheReadTokens());
        assertEquals(1, observation.reasoningTokens());
        assertEquals("stop", observation.finishReason());
        assertFalse(observation.toString().contains("final answer from mock OpenAI"));
    }

    @Test
    void finalContentAndPrivateReasoningRemainSeparateWithDeliveryContract() throws Exception {
        String prompt=com.paicli.config.Config.defaults().effectiveSystemPrompt();
        var provider=new OpenAICompatibleProvider("http://localhost:"+port,"synthetic-key","test-model",prompt,30);
        var result=provider.chat(List.of(AgentMessage.userGoal("final channels fixture")),List.of());
        assertEquals(ModelResponse.ResponseType.FINAL,result.type());
        assertEquals("Result: the source contains the literal 'Let me think'.\n```java\nString label = \"analysis\";\n```",result.content());
        assertFalse(result.toString().contains("PRIVATE_REASONING_SENTINEL"));
        var request=MAPPER.readTree(lastRequestBody.get());
        assertEquals("system",request.path("messages").get(0).path("role").asText());
        assertEquals(prompt,request.path("messages").get(0).path("content").asText());
        assertFalse(request.toString().contains("PRIVATE_REASONING_SENTINEL"));
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
        assertEquals(".", response.toolCall().arguments().get("path"));
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
    void apiKeySentInAuthHeader() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String auth = lastAuthHeader.get();
        assertNotNull(auth);
        assertTrue(auth.startsWith("Bearer "));
        assertTrue(auth.contains("test-api-key-12345"));
    }

    @Test
    void apiKeyNotInRequestBody() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String body = lastRequestBody.get();
        assertFalse(body.contains("test-api-key-12345"));
    }

    @Test
    void connectionErrorReturnsTypedProviderFailure() {
        var provider = new OpenAICompatibleProvider(
                "http://localhost:19999",
                "key", "model", "system", 5
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
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_SERVER_FAILURE,
                http.providerErrorKind());
        assertFalse(http.toString().contains("secret-bearing-raw-body"));

        ModelResponse parse = createProvider().chat(
                List.of(AgentMessage.userGoal("provider parse failure")), List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE,
                parse.providerErrorKind());
    }

    @Test
    void missingUsageIsProviderNotReturnedRatherThanEstimated() {
        OpenAICompatibleProvider provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("provider missing usage")), List.of());

        ProviderTransportEvidence evidence = provider.takeTransportEvidence();

        assertEquals(ProviderTransportEvidence.TransportState.RESPONSE_RECEIVED,
                evidence.transportState());
        assertEquals(ProviderTransportEvidence.UsageProvenance.PROVIDER_NOT_RETURNED,
                evidence.usageProvenance());
        assertEquals("PROVIDER_RESPONSE_USAGE_ABSENT", evidence.unavailableReason());
        assertNull(provider.takeObservation().inputTokens());
    }

    @Test
    void parserFailureKeepsReceivedTransportAndUnavailableReason() {
        OpenAICompatibleProvider provider = createProvider();
        ModelResponse response = provider.chat(
                List.of(AgentMessage.userGoal("provider parse failure")), List.of());

        ProviderTransportEvidence evidence = provider.takeTransportEvidence();

        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE,
                response.providerErrorKind());
        assertEquals(ProviderTransportEvidence.TransportState.RESPONSE_RECEIVED,
                evidence.transportState());
        assertEquals(ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                evidence.usageProvenance());
        assertEquals("RESPONSE_PARSE_FAILURE", evidence.unavailableReason());
    }

    @Test
    void timeoutHasTerminalTransportFailureWithoutUsageGuess() {
        OpenAICompatibleProvider provider = new OpenAICompatibleProvider(
                "https://api.example.invalid", ProviderCredentialSource.fixed("test-key"),
                "model", "system", 1, OpenAICompatiblePreset.generic(),
                "disabled", null, request -> {
                    throw new HttpTimeoutException("synthetic timeout");
                });

        ModelResponse response = provider.chat(List.of(AgentMessage.userGoal("timeout")),
                List.of());
        ProviderTransportEvidence evidence = provider.takeTransportEvidence();

        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                response.providerErrorKind());
        assertEquals(ProviderTransportEvidence.TransportState.TRANSPORT_FAILED,
                evidence.transportState());
        assertEquals(ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                evidence.usageProvenance());
        assertEquals("TRANSPORT_TIMEOUT", evidence.unavailableReason());
        assertNull(evidence.httpStatus());
    }

    @Test
    void requestIncludesModelName() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String body = lastRequestBody.get();
        assertTrue(body.contains("test-model"));
    }

    @Test
    void requestIncludesStreamFalse() {
        var provider = createProvider();
        provider.chat(List.of(AgentMessage.userGoal("test")), List.of());

        String body = lastRequestBody.get();
        assertTrue(body.contains("\"stream\":false") || body.contains("\"stream\" : false"));
    }
}
