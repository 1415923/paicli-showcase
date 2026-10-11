package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.OpenAICompatiblePreset;
import com.paicli.provider.OpenAICompatibleProvider;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exact structural regression for the sanitized v2 task-005 continuation trace. */
class DeepSeekContinuationTraceRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void durableProjectionKeepsMultiToolTurnAtomicBeforeLaterContinuation()
            throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream(
                "/com/paicli/benchmark/v2-task-005-continuation-shape.json")) {
            assertNotNull(input);
            fixture = JSON.readTree(input);
        }
        assertFalse(fixture.path("containsProviderPrivateContent").asBoolean(true));
        List<String> batchIds = ids(fixture.path("turns").get(0)
                .path("assistantToolCallIds"));
        List<String> singleIds = ids(fixture.path("turns").get(1)
                .path("assistantToolCallIds"));
        assertEquals(batchIds, ids(fixture.path("turns").get(0).path("toolResultIds")));
        assertEquals(singleIds, ids(fixture.path("turns").get(1).path("toolResultIds")));

        AtomicInteger networkRequests = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            int ordinal = networkRequests.incrementAndGet();
            requests.add(JSON.readTree(exchange.getRequestBody()));
            if (ordinal == 1) {
                respond(exchange, toolBatchResponse(batchIds));
            } else if (ordinal == 2) {
                respond(exchange, singleToolResponse(singleIds.getFirst()));
            } else {
                respond(exchange, """
                        {"choices":[{"finish_reason":"stop","message":{
                        "role":"assistant","content":"{\\"userAnswer\\":\\"sanitized regression complete\\"}"}}],
                        "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """);
            }
        });
        server.start();
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var root = admitted.root();
            AgentMessage goal = AgentMessage.userGoal("sanitized task input");
            AgentMessage runtime = AgentMessage.runtimeStepPrompt("sanitized runtime prompt");
            admitted.guard().appendEvent(0, goal);
            admitted.guard().appendEvent(0, runtime);
            List<AgentMessage> messages = new ArrayList<>(List.of(goal, runtime));

            Config providerConfig = Config.defaults().withProvider("openai",
                    "deepseek-v4-flash", "http://localhost:" + server.getAddress().getPort(),
                    "DEEPSEEK_API_KEY").withProviderPreset("deepseek");
            OpenAICompatibleProvider adapter = new OpenAICompatibleProvider(
                    "http://localhost:" + server.getAddress().getPort(), "test-secret",
                    "deepseek-v4-flash", "system", 5,
                    OpenAICompatiblePreset.from(providerConfig), "enabled", "max");
            ContextualModelProvider provider = new ContextualModelProvider(adapter, state,
                    workspace, root.taskId().value(), "openai", "deepseek-v4-flash",
                    OperationKind.CHAT, new CapabilityProfile("configured", 65_536,
                    LimitProvenance.CONFIGURED_LIMIT, 32_768, 2_048), false, "system");
            List<ToolDefinition> tools = List.of(tool("list_files"), tool("grep"),
                    tool("read_file"));

            ModelResponse first = provider.chat(OperationKind.CHAT, "trace-1", messages,
                    tools, admitted.guard());
            assertEquals(ModelResponse.ResponseType.TOOL_CALL, first.type());
            assertEquals(2, first.toolCalls().size());
            appendBatch(state, admitted, messages, 1, first);

            ModelResponse second = provider.chat(OperationKind.TOOL_CONTINUATION, "trace-2",
                    messages, tools, admitted.guard());
            assertEquals(ModelResponse.ResponseType.TOOL_CALL, second.type());
            assertEquals(1, second.toolCalls().size());
            appendSingle(state, admitted, messages, 2, second);

            ModelResponse third = provider.chat(OperationKind.TOOL_CONTINUATION, "trace-3",
                    messages, tools, admitted.guard());
            assertEquals(ModelResponse.ResponseType.FINAL, third.type());
            assertEquals(3, networkRequests.get(),
                    "the exact trace must reach the third network request");
            assertContinuationShape(requests.get(2), batchIds, singleIds.getFirst());
        } finally {
            server.stop(0);
        }
    }

    @Test void simpleLoopUsesTheSameAdapterContinuationShape() throws Exception {
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream(
                "/com/paicli/benchmark/v2-task-005-continuation-shape.json")) {
            assertNotNull(input);
            fixture = JSON.readTree(input);
        }
        List<String> batchIds = ids(fixture.path("turns").get(0)
                .path("assistantToolCallIds"));
        String singleId = fixture.path("turns").get(1)
                .path("assistantToolCallIds").get(0).asText();
        AtomicInteger networkRequests = new AtomicInteger();
        List<JsonNode> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", exchange -> {
            int ordinal = networkRequests.incrementAndGet();
            requests.add(JSON.readTree(exchange.getRequestBody()));
            respond(exchange, ordinal == 1 ? toolBatchResponse(batchIds)
                    : ordinal == 2 ? singleToolResponse(singleId) : """
                    {"choices":[{"finish_reason":"stop","message":{
                    "role":"assistant","content":"sanitized regression complete"}}],
                    "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                    """);
        });
        server.start();
        try {
            Config providerConfig = Config.defaults().withProvider("openai",
                    "deepseek-v4-flash", "http://localhost:" + server.getAddress().getPort(),
                    "DEEPSEEK_API_KEY").withProviderPreset("deepseek");
            OpenAICompatibleProvider adapter = new OpenAICompatibleProvider(
                    "http://localhost:" + server.getAddress().getPort(), "test-secret",
                    "deepseek-v4-flash", "system", 5,
                    OpenAICompatiblePreset.from(providerConfig), "enabled", "max");
            List<ToolDefinition> tools = List.of(tool("list_files"), tool("grep"),
                    tool("read_file"));
            List<AgentMessage> messages = new ArrayList<>(List.of(
                    AgentMessage.userGoal("sanitized task input")));
            ModelResponse first = adapter.chat(messages, tools);
            messages.add(batchAction(first));
            first.toolCalls().forEach(call -> messages.add(
                    AgentMessage.observationForToolCall("sanitized result", call.providerCallId())));
            ModelResponse second = adapter.chatForOperation("TOOL_CONTINUATION", messages, tools);
            messages.add(singleAction(second));
            messages.add(AgentMessage.observationForToolCall(
                    "sanitized result", second.toolCall().providerCallId()));
            ModelResponse third = adapter.chatForOperation("TOOL_CONTINUATION", messages, tools);
            assertEquals(ModelResponse.ResponseType.FINAL, third.type());
            assertEquals(3, networkRequests.get());
            assertContinuationShape(requests.get(2), batchIds, singleId);
        } finally {
            server.stop(0);
        }
    }

    private static void appendBatch(StateStore state, R3ContextTestFixtures.Admitted admitted,
            List<AgentMessage> messages, int iteration, ModelResponse response) throws Exception {
        var json = JSON.createArrayNode();
        for (ToolCall call : response.toolCalls()) {
            var item = json.addObject();
            item.put("id", call.providerCallId());
            item.put("type", "function");
            item.put("x_paicli_private_continuation", true);
            var function = item.putObject("function");
            function.put("name", call.name());
            function.put("arguments", JSON.writeValueAsString(call.arguments()));
        }
        AgentMessage action = AgentMessage.actionWithToolCall("sanitized batch",
                response.toolCalls().getFirst().providerCallId(), json.toString());
        admitted.guard().appendEvent(iteration, action);
        messages.add(action);
        for (int index = 0; index < response.toolCalls().size(); index++) {
            appendResult(state, admitted, messages, iteration,
                    response.toolCalls().get(index), index + 1);
        }
    }

    private static AgentMessage batchAction(ModelResponse response) {
        var json = JSON.createArrayNode();
        for (ToolCall call : response.toolCalls()) {
            var item = json.addObject();
            item.put("id", call.providerCallId());
            item.put("type", "function");
            item.put("x_paicli_private_continuation", true);
            var function = item.putObject("function");
            function.put("name", call.name());
            function.put("arguments", json(call.arguments()));
        }
        return AgentMessage.actionWithToolCall("sanitized batch",
                response.toolCalls().getFirst().providerCallId(), json.toString());
    }

    private static AgentMessage singleAction(ModelResponse response) {
        ToolCall call = response.toolCall();
        var item = JSON.createObjectNode();
        item.put("id", call.providerCallId());
        item.put("type", "function");
        item.put("x_paicli_private_continuation", true);
        var function = item.putObject("function");
        function.put("name", call.name());
        function.put("arguments", json(call.arguments()));
        return AgentMessage.actionWithToolCall("sanitized single",
                call.providerCallId(), item.toString());
    }

    private static void appendSingle(StateStore state, R3ContextTestFixtures.Admitted admitted,
            List<AgentMessage> messages, int iteration, ModelResponse response) throws Exception {
        ToolCall call = response.toolCall();
        var item = JSON.createObjectNode();
        item.put("id", call.providerCallId());
        item.put("type", "function");
        item.put("x_paicli_private_continuation", true);
        var function = item.putObject("function");
        function.put("name", call.name());
        function.put("arguments", JSON.writeValueAsString(call.arguments()));
        AgentMessage action = AgentMessage.actionWithToolCall("sanitized single",
                call.providerCallId(), item.toString());
        admitted.guard().appendEvent(iteration, action);
        messages.add(action);
        appendResult(state, admitted, messages, iteration, call, null);
    }

    private static void appendResult(StateStore state, R3ContextTestFixtures.Admitted admitted,
            List<AgentMessage> messages, int iteration, ToolCall call, Integer siblingOrdinal)
            throws Exception {
        String id = call.providerCallId();
        String arguments = JSON.writeValueAsString(call.arguments());
        ToolResult result = ToolResult.success("sanitized result for " + call.name());
        var occurrence = siblingOrdinal == null
                ? admitted.guard().admitOccurrence(iteration, id, call)
                : admitted.guard().admitOccurrence(iteration, siblingOrdinal, id, call);
        admitted.guard().recordToolCall(iteration, id, call.name(), arguments, result);
        admitted.guard().completeOccurrence(occurrence, result);
        AgentMessage observation = AgentMessage.observationForToolCall(result.output(), id);
        admitted.guard().appendEvent(iteration, observation);
        messages.add(observation);
    }

    private static void assertContinuationShape(JsonNode request, List<String> batchIds,
            String singleId) {
        List<String> announced = new ArrayList<>();
        List<String> observed = new ArrayList<>();
        boolean batchPreserved = false;
        boolean reasoningPreserved = false;
        for (JsonNode message : request.path("messages")) {
            if ("assistant".equals(message.path("role").asText())
                    && message.path("tool_calls").isArray()) {
                List<String> ids = new ArrayList<>();
                message.path("tool_calls").forEach(call -> ids.add(call.path("id").asText()));
                announced.addAll(ids);
                if (ids.equals(batchIds)) batchPreserved = true;
                if (!ids.isEmpty() && message.has("reasoning_content")) reasoningPreserved = true;
            } else if ("tool".equals(message.path("role").asText())) {
                observed.add(message.path("tool_call_id").asText());
            }
        }
        assertTrue(batchPreserved, "the two-call assistant turn must remain atomic");
        assertTrue(reasoningPreserved, "private continuation reasoning must be reconstructed");
        assertEquals(List.of(batchIds.get(0), batchIds.get(1), singleId), announced);
        assertEquals(announced, observed);
    }

    private static ToolDefinition tool(String name) {
        return new ToolDefinition(name, "sanitized", JSON.createObjectNode()
                .put("type", "object"));
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String toolBatchResponse(List<String> ids) {
        var root = responseBase("tool_calls", "sanitized-private-batch");
        var message = (com.fasterxml.jackson.databind.node.ObjectNode)
                root.path("choices").get(0).path("message");
        var calls = message.putArray("tool_calls");
        for (int i = 0; i < ids.size(); i++) {
            var call = calls.addObject();
            call.put("id", ids.get(i));
            call.put("type", "function");
            var function = call.putObject("function");
            function.put("name", i == 0 ? "list_files" : "grep");
            function.put("arguments", "{}");
        }
        return json(root);
    }

    private static String singleToolResponse(String id) {
        var root = responseBase("tool_calls", "sanitized-private-single");
        var message = (com.fasterxml.jackson.databind.node.ObjectNode)
                root.path("choices").get(0).path("message");
        var call = message.putArray("tool_calls").addObject();
        call.put("id", id);
        call.put("type", "function");
        var function = call.putObject("function");
        function.put("name", "read_file");
        function.put("arguments", "{}");
        return json(root);
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode responseBase(
            String finishReason, String reasoning) {
        var root = JSON.createObjectNode();
        var choice = root.putArray("choices").addObject();
        choice.put("finish_reason", finishReason);
        var message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", "");
        message.put("reasoning_content", reasoning);
        var usage = root.putObject("usage");
        usage.put("prompt_tokens", 1);
        usage.put("completion_tokens", 1);
        usage.put("total_tokens", 2);
        return root;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
