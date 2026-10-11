package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SharedToolBatchPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void frozenPolicyHashMatchesProtocolManifest() {
        assertEquals("1b42c06a1e7552b9f6c596451642517256467ed8fcc1064934848a553d3aa427",
                SharedToolBatchPolicy.policyHash());
        assertEquals("85f1015a0e08da8a8d756e73286cdc8b7a8d2f8f575761a1564bd2ebf34d2165",
                SharedToolBatchPolicy.telemetryHash());
        assertNotEquals(SharedToolBatchPolicy.policyHash(),
                SharedToolBatchPolicy.telemetryHash());
    }

    @Test
    void twoEffectfulCallsExecuteNoneReturnTypedResultsAndContinueOnce() throws Exception {
        CountingTool write = new CountingTool("write_file");
        ToolRegistry registry = registry(write);
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation = new AtomicReference<>();
        CapturingObserver observer = new CapturingObserver();
        ModelProvider provider = (messages, tools) -> {
            if (providerCalls.getAndIncrement() == 0) return batch("private batch",
                    List.of(call("write_file", "call-a"), call("write_file", "call-b")), true);
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("serialized");
        };

        assertTrue(loop(provider, registry, observer, 4).run("write safely").completed());
        assertEquals(0, write.executions.get());
        assertEquals(2, providerCalls.get());
        List<AgentMessage> actions = messages(continuation.get(), AgentMessage.MessageType.ACTION);
        List<AgentMessage> results = messages(continuation.get(), AgentMessage.MessageType.OBSERVATION);
        assertEquals(1, actions.size());
        assertTrue(actions.getFirst().toolCallJson().contains("call-a"));
        assertTrue(actions.getFirst().toolCallJson().contains("call-b"));
        assertTrue(actions.getFirst().toolCallJson().contains("x_paicli_private_continuation"));
        assertEquals(List.of("call-a", "call-b"), results.stream()
                .map(AgentMessage::toolCallId).toList());
        for (AgentMessage result : results) {
            var typed = JSON.readTree(result.content());
            assertEquals("NOT_EXECUTED", typed.path("status").asText());
            assertEquals(SharedToolBatchPolicy.NOT_EXECUTED_CODE,
                    typed.path("code").asText());
            assertEquals(SharedToolBatchPolicy.SERIALIZATION_INSTRUCTION,
                    typed.path("instruction").asText());
        }
        assertEquals(List.of(new AgentLoopObserver.EffectfulBatchFact(true, 2, true,
                        AgentLoopObserver.EffectfulBatchFact.NormalizationOutcome
                                .SERIALIZATION_CONTINUATION_ISSUED)),
                observer.events);
    }

    @Test
    void repeatedEffectfulBatchesConsumeTheExistingIterationBudget() {
        CountingTool write = new CountingTool("write_file");
        ToolRegistry registry = registry(write);
        AtomicInteger providerCalls = new AtomicInteger();
        CapturingObserver observer = new CapturingObserver();
        ModelProvider provider = (messages, tools) -> {
            int turn = providerCalls.incrementAndGet();
            return batch("repeat", List.of(call("write_file", "call-" + turn + "-a"),
                    call("write_file", "call-" + turn + "-b")), false);
        };

        AgentResult result = loop(provider, registry, observer, 3).run("write safely");
        assertEquals(AgentResult.Outcome.MAX_ITERATIONS, result.outcome());
        assertEquals(3, result.iterations());
        assertEquals(3, providerCalls.get());
        assertEquals(0, write.executions.get());
        assertEquals(3, observer.events.size());
        assertTrue(observer.events.stream().allMatch(event -> event.normalizationOutcome()
                == AgentLoopObserver.EffectfulBatchFact.NormalizationOutcome
                        .SERIALIZATION_CONTINUATION_ISSUED));
    }

    @Test
    void mixedReadAndEffectfulBatchNeverPartiallyExecutes() {
        CountingTool read = new CountingTool("read_file");
        CountingTool write = new CountingTool("write_file");
        ToolRegistry registry = registry(read, write);
        AtomicInteger providerCalls = new AtomicInteger();
        ModelProvider provider = (messages, tools) -> providerCalls.getAndIncrement() == 0
                ? batch("mixed", List.of(call("read_file", "read-a"),
                        call("write_file", "write-a")), false)
                : ModelResponse.finalAnswer("done");

        assertTrue(new AgentLoop(provider, registry, 4).run("mixed").completed());
        assertEquals(0, read.executions.get());
        assertEquals(0, write.executions.get());
    }

    @Test
    void singleEffectfulCallKeepsExistingExecutionBehavior() {
        CountingTool write = new CountingTool("write_file");
        ToolRegistry registry = registry(write);
        AtomicInteger providerCalls = new AtomicInteger();
        ModelProvider provider = (messages, tools) -> providerCalls.getAndIncrement() == 0
                ? ModelResponse.toolCallResponse("single", call("write_file", "single-a"))
                : ModelResponse.finalAnswer("done");

        assertTrue(new AgentLoop(provider, registry, 4).run("single").completed());
        assertEquals(1, write.executions.get());
    }

    private static ModelResponse batch(String content, List<ToolCall> calls,
            boolean privateContinuation) {
        return ModelResponse.toolCallBatchResponse(content, calls,
                ProviderResponseEnvelope.inspect(calls.stream()
                        .map(ToolCall::providerCallId).toList(), false), privateContinuation);
    }

    private static ToolCall call(String name, String id) {
        return new ToolCall(name, Map.of("path", "file.txt", "content", "x"), id);
    }

    private static List<AgentMessage> messages(List<AgentMessage> messages,
            AgentMessage.MessageType type) {
        return messages.stream().filter(message -> message.type() == type).toList();
    }

    private static ToolRegistry registry(CountingTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (CountingTool tool : tools) registry.register(tool);
        return registry;
    }

    private static AgentLoop loop(ModelProvider provider, ToolRegistry registry,
            AgentLoopObserver observer, int maxIterations) {
        return new AgentLoop(provider, registry, maxIterations, null, null,
                null, null, null, null, observer);
    }

    private static final class CapturingObserver implements AgentLoopObserver {
        private final List<EffectfulBatchFact> events = new ArrayList<>();

        @Override
        public void effectfulBatchNormalized(String taskId, int iteration,
                EffectfulBatchFact fact) {
            events.add(fact);
        }
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public String description() { return "counting Tool"; }
        @Override public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), JsonNodeFactory.instance.objectNode());
        }
        @Override public ToolResult execute(ToolCall call) {
            executions.incrementAndGet();
            return ToolResult.success("executed");
        }
    }
}
