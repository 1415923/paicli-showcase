package com.paicli.provider;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;

import java.util.List;

/** Provider capable of serializing a logical request exactly once before transport retries. */
public interface PreparedTurnProvider extends ModelProvider {
    PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions);

    default PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement) {
        if (!OutputRequirement.LEGACY.equals(requirement))
            throw new IllegalArgumentException("OUTPUT_REQUIREMENT_NOT_FORWARDED");
        return prepareTurn(operation, messages, tools);
    }

    @Override
    default ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement) {
        return prepareTurn(operation, messages, tools, requirement).execute();
    }

    @Override
    default ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
        return chatForOperation("CHAT", messages, tools);
    }

    @Override
    default ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        return prepareTurn(operation, messages, tools).execute();
    }
}
