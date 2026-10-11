package com.paicli.provider;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;

import java.util.List;

/**
 * Abstraction over a language model.
 * <p>
 * Implementations may call a local mock, Ollama, OpenAI-compatible API, etc.
 */
public interface ModelProvider {

    /** Local managed-runtime answer format opt-in, not a vendor JSON/schema capability. */
    default boolean usesManagedAnswerEnvelope() { return false; }

    /** Existing installations stay explicitly prompt-only until a native combination is qualified. */
    default OutputRequirement.Mode managedOutputMode() { return OutputRequirement.Mode.PROMPT_ONLY; }

    /** A decorator must explicitly forward non-legacy requirements; default methods never drop them. */
    default void validateOutputRequirement(OutputRequirement requirement, boolean hasTools) {
        if (!OutputRequirement.LEGACY.equals(requirement))
            throw new IllegalArgumentException("OUTPUT_REQUIREMENT_NOT_IMPLEMENTED");
    }

    default ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement) {
        if (!OutputRequirement.LEGACY.equals(requirement))
            throw new IllegalArgumentException("OUTPUT_REQUIREMENT_NOT_FORWARDED");
        return chatForOperation(operation, messages, tools);
    }

    /**
     * Send the conversation history and available tool definitions to the model,
     * and get back a response.
     *
     * @param messages        full conversation so far
     * @param toolDefinitions tools the model is allowed to call (may be empty)
     * @return the model's response — either a final answer or a tool call request
     */
    ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> toolDefinitions);

    /** Operation-aware compatibility seam; existing providers retain their current behavior. */
    default ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions) {
        return chat(messages, toolDefinitions);
    }
}
