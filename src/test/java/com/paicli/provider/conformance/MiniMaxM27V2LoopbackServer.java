package com.paicli.provider.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Scripted public-fixture loopback. Raw bodies stay only in test memory. */
final class MiniMaxM27V2LoopbackServer implements AutoCloseable {
    static final String SECRET = "loopback-only-minimax-secret";
    static final String TOOL_THINKING = "<think>synthetic reasoning</think>\n";
    static final String FINAL_THINKING = "<think>synthetic final reasoning</think>\n";
    private final HttpServer server;
    private final JsonNode fixtureContract;
    private final Map<String, Integer> turns = new HashMap<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    MiniMaxM27V2LoopbackServer(Path project) throws IOException {
        fixtureContract = MiniMaxM27V2ProbeContract.load(project, "fixtures.json");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void assertHealthy() {
        Throwable error = failure.get();
        if (error != null) throw new AssertionError("loopback wire assertion failed", error);
        if (!turns.equals(Map.of("F1", 1, "F2", 2, "F3", 2, "F4", 2))) {
            throw new AssertionError("unexpected logical request counts: " + turns);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                throw new AssertionError("method drift");
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + SECRET).equals(auth)) throw new AssertionError("auth header drift");
            if (!"application/json".equals(exchange.getRequestHeaders()
                    .getFirst("Content-Type"))) throw new AssertionError("content type drift");
            JsonNode request = MiniMaxM27V2ProbeContract.JSON.readTree(
                    exchange.getRequestBody().readAllBytes());
            String fixture = fixture(request);
            int turn = turns.merge(fixture, 1, Integer::sum);
            assertFrozenRequest(request, fixture);
            ObjectNode response = turn == 1
                    ? firstResponse(fixture) : continuationResponse(request, fixture, turn);
            respond(exchange, 200, response);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
            ObjectNode response = MiniMaxM27V2ProbeContract.JSON.createObjectNode();
            response.put("error", "loopback-assertion-failed");
            respond(exchange, 500, response);
        }
    }

    private void assertFrozenRequest(JsonNode request, String fixture) {
        if (!request.isObject()) throw new AssertionError("request must be object");
        List<String> expected = "F1".equals(fixture)
                ? List.of("max_tokens", "messages", "model", "stream")
                : List.of("max_tokens", "messages", "model", "stream", "tools");
        List<String> actual = new ArrayList<>();
        request.fieldNames().forEachRemaining(actual::add);
        actual.sort(String::compareTo);
        if (!actual.equals(expected)) throw new AssertionError("request field drift: " + actual);
        if (!MiniMaxM27V2ProbeContract.MODEL.equals(request.path("model").asText())) {
            throw new AssertionError("model drift");
        }
        if (request.path("stream").asBoolean(true)
                || request.path("max_tokens").asInt() != 16_384) {
            throw new AssertionError("stream/max_tokens drift");
        }
        for (String omitted : List.of("reasoning", "reasoning_effort", "thinking",
                "reasoning_split", "tool_choice", "parallel_tool_calls",
                "temperature", "top_p", "service_tier")) {
            if (request.has(omitted)) throw new AssertionError("forbidden request field: " + omitted);
        }
        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()
                || !"system".equals(messages.get(0).path("role").asText())
                || !MiniMaxM27V2ProbeContract.SYSTEM_PROMPT.equals(
                        messages.get(0).path("content").asText())) {
            throw new AssertionError("system message drift");
        }
        if (!"F1".equals(fixture)) assertToolSchemas(request.path("tools"), fixture);
    }

    private void assertToolSchemas(JsonNode tools, String fixture) {
        JsonNode definition = null;
        for (JsonNode value : fixtureContract.path("fixtures")) {
            if (fixture.equals(value.path("id").asText())) definition = value;
        }
        if (definition == null || tools.size() != definition.path("tools").size()) {
            throw new AssertionError("Tool count drift");
        }
        Map<String, JsonNode> expectedSchemas = new HashMap<>();
        for (JsonNode expectedName : definition.path("tools")) {
            expectedSchemas.put(expectedName.asText(), fixtureContract.path("tool_schemas")
                    .path(expectedName.asText()));
        }
        for (int index = 0; index < tools.size(); index++) {
            JsonNode function = tools.get(index).path("function");
            String actualName = function.path("name").asText();
            JsonNode expectedSchema = expectedSchemas.remove(actualName);
            if (!"function".equals(tools.get(index).path("type").asText())
                    || expectedSchema == null
                    || !expectedSchema.equals(function.path("parameters"))) {
                throw new AssertionError("Tool schema drift at index " + index
                        + ": actual=" + actualName
                        + ", actual_parameters=" + function.path("parameters"));
            }
        }
        if (!expectedSchemas.isEmpty()) {
            throw new AssertionError("Missing Tool schemas: " + expectedSchemas.keySet());
        }
    }

    private ObjectNode firstResponse(String fixture) {
        if ("F1".equals(fixture)) return finalResponse(fixture, "MINIMAX_F1_OK");
        ObjectNode response = base(fixture, "tool_calls");
        ObjectNode message = (ObjectNode) response.path("choices").get(0).path("message");
        message.put("content", TOOL_THINKING);
        ArrayNode calls = message.putArray("tool_calls");
        switch (fixture) {
            case "F2" -> addCall(calls, "minimax-f2-call", "lookup_fixture_fact",
                    "{\"key\":\"alpha\"}");
            case "F3" -> {
                addCall(calls, "minimax-f3-fact", "lookup_fixture_fact",
                        "{\"key\":\"alpha\"}");
                addCall(calls, "minimax-f3-label", "lookup_fixture_label",
                        "{\"code\":\"beta\"}");
            }
            case "F4" -> {
                addCall(calls, "minimax-f4-read", "lookup_fixture_fact",
                        "{\"key\":\"alpha\"}");
                addCall(calls, "minimax-f4-effect", "effect_counter",
                        "{\"action\":\"increment-once\"}");
            }
            default -> throw new AssertionError("unknown fixture");
        }
        return response;
    }

    private ObjectNode continuationResponse(JsonNode request, String fixture, int turn) {
        if (turn != 2 || "F1".equals(fixture)) {
            throw new AssertionError("unexpected continuation");
        }
        List<String> assistantIds = new ArrayList<>();
        List<String> resultIds = new ArrayList<>();
        int assistantThinkingTurns = 0;
        int notExecuted = 0;
        for (JsonNode message : request.path("messages")) {
            if ("assistant".equals(message.path("role").asText())
                    && message.path("tool_calls").isArray()) {
                if (TOOL_THINKING.equals(message.path("content").asText())) {
                    assistantThinkingTurns++;
                }
                for (JsonNode call : message.path("tool_calls")) {
                    assistantIds.add(call.path("id").asText());
                }
            }
            if ("tool".equals(message.path("role").asText())) {
                resultIds.add(message.path("tool_call_id").asText());
                if (message.path("content").asText().contains(
                        "\"status\":\"NOT_EXECUTED\"")) notExecuted++;
            }
        }
        List<String> expected = switch (fixture) {
            case "F2" -> List.of("minimax-f2-call");
            case "F3" -> List.of("minimax-f3-fact", "minimax-f3-label");
            case "F4" -> List.of("minimax-f4-read", "minimax-f4-effect");
            default -> throw new AssertionError("unknown fixture");
        };
        if (!assistantIds.equals(expected) || !resultIds.equals(expected)) {
            throw new AssertionError("tool_call_id continuation binding drift");
        }
        if (assistantThinkingTurns != 1) {
            throw new AssertionError("assistant Tool-turn thinking content was not preserved");
        }
        if ("F4".equals(fixture) && notExecuted != 2) {
            throw new AssertionError("F4 NOT_EXECUTED closure drift");
        }
        return finalResponse(fixture, "MINIMAX_" + fixture + "_OK");
    }

    private static void addCall(ArrayNode calls, String id, String name, String arguments) {
        ObjectNode call = calls.addObject();
        call.put("id", id);
        call.put("type", "function");
        ObjectNode function = call.putObject("function");
        function.put("name", name);
        function.put("arguments", arguments);
    }

    private static ObjectNode finalResponse(String fixture, String content) {
        ObjectNode response = base(fixture, "stop");
        ((ObjectNode) response.path("choices").get(0).path("message")).put(
                "content", FINAL_THINKING + content);
        return response;
    }

    private static ObjectNode base(String fixture, String finishReason) {
        ObjectNode response = MiniMaxM27V2ProbeContract.JSON.createObjectNode();
        response.put("id", "chatcmpl-minimax-" + fixture.toLowerCase());
        response.put("object", "chat.completion");
        ObjectNode usage = response.putObject("usage");
        usage.put("prompt_tokens", 100);
        usage.put("completion_tokens", 20);
        usage.put("total_tokens", 120);
        usage.putObject("prompt_tokens_details").put("cached_tokens", 10);
        ObjectNode choice = response.putArray("choices").addObject();
        choice.put("index", 0);
        choice.put("finish_reason", finishReason);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        return response;
    }

    private static String fixture(JsonNode request) {
        for (JsonNode message : request.path("messages")) {
            String content = message.path("content").asText();
            for (String id : List.of("F1", "F2", "F3", "F4")) {
                if (content.contains("Fixture " + id + ".")) return id;
            }
        }
        throw new AssertionError("fixture prompt not found");
    }

    private static void respond(HttpExchange exchange, int status, JsonNode value)
            throws IOException {
        byte[] payload = MiniMaxM27V2ProbeContract.JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (var output = exchange.getResponseBody()) { output.write(payload); }
    }

    @Override public void close() { server.stop(0); }
}
