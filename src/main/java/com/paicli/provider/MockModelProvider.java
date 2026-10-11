package com.paicli.provider;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

/**
 * A deterministic mock that returns pre-configured responses in order.
 * <p>
 * When the queue is exhausted, returns a default final response saying
 * "MockModelProvider: no more pre-configured responses."
 * <p>
 * Usage:
 * <pre>{@code
 * var mock = new MockModelProvider(List.of(
 *     ModelResponse.toolCallResponse("I'll list files", new ToolCall("list_files", Map.of("path", "."))),
 *     ModelResponse.finalAnswer("Here are the files: ...")
 * ));
 * }</pre>
 */
public class MockModelProvider implements ModelProvider {

    private static final String DEFAULT_FALLBACK = "MockModelProvider: no more pre-configured responses.";

    private final Queue<ModelResponse> responses;

    public MockModelProvider(List<ModelResponse> preconfiguredResponses) {
        this.responses = new ArrayDeque<>(preconfiguredResponses);
    }

    @Override
    public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> toolDefinitions) {
        ModelResponse next = responses.poll();
        if (next != null) {
            return next;
        }
        return ModelResponse.finalAnswer(DEFAULT_FALLBACK);
    }

    /** Returns true if all pre-configured responses have been consumed. */
    public boolean isExhausted() {
        return responses.isEmpty();
    }
}
