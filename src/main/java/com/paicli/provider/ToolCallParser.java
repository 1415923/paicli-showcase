package com.paicli.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.ToolCall;

import java.util.List;
import java.util.Map;

/**
 * Parses tool call JSON from different LLM API formats into a unified ToolCall.
 * <p>
 * Supports:
 * <ul>
 *   <li>Ollama: {"function": {"name": "...", "arguments": {...}}}</li>
 *   <li>OpenAI: {"id": "...", "type": "function", "function": {"name": "...", "arguments": "{...}"}}</li>
 * </ul>
 */
public class ToolCallParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Parse a single tool call JSON node into a ToolCall.
     *
     * @param toolCallNode the JSON node representing one tool call
     * @return parsed ToolCall, or null if parsing fails
     */
    public static ToolCallResult parse(JsonNode toolCallNode) {
        if (toolCallNode == null || toolCallNode.isNull()) {
            return null;
        }

        try {
            // Extract id (OpenAI format has it, Ollama may not)
            String id = null;
            if (toolCallNode.has("id")) {
                id = toolCallNode.get("id").asText();
            }

            // Extract function node
            JsonNode functionNode = toolCallNode.get("function");
            if (functionNode == null) {
                // Ollama sometimes puts name/arguments at top level
                if (toolCallNode.has("name")) {
                    return parseTopLevel(toolCallNode, id);
                }
                return null;
            }

            String name = functionNode.has("name") ? functionNode.get("name").asText() : null;
            if (name == null || name.isBlank()) {
                return null;
            }

            // Parse arguments — may be a JSON object (Ollama) or a JSON string (OpenAI)
            Map<String, Object> arguments = parseArguments(functionNode.get("arguments"));
            if (arguments == null) {
                return null;
            }

            return new ToolCallResult(new ToolCall(name, arguments, id), id);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse the first tool call from a list (most common: model requests one tool at a time).
     */
    public static ToolCallResult parseFirst(JsonNode toolCallsArray) {
        if (toolCallsArray == null || !toolCallsArray.isArray() || toolCallsArray.isEmpty()) {
            return null;
        }
        return parse(toolCallsArray.get(0));
    }

    /**
     * Parse all tool calls from a list.
     */
    public static List<ToolCallResult> parseAll(JsonNode toolCallsArray) {
        if (toolCallsArray == null || !toolCallsArray.isArray()) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(toolCallsArray.spliterator(), false)
                .map(ToolCallParser::parse)
                .filter(r -> r != null)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseArguments(JsonNode argsNode) {
        if (argsNode == null || argsNode.isNull()) {
            return Map.of();
        }
        try {
            if (argsNode.isObject()) {
                // Ollama: arguments is a JSON object
                return MAPPER.convertValue(argsNode, new TypeReference<>() {});
            }
            if (argsNode.isTextual()) {
                // OpenAI: arguments is a JSON string containing an object
                JsonNode parsed = MAPPER.readTree(argsNode.asText());
                if (!parsed.isObject()) {
                    return null;
                }
                return MAPPER.convertValue(parsed, new TypeReference<>() {});
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static ToolCallResult parseTopLevel(JsonNode node, String id) {
        try {
            String name = node.get("name").asText();
            if (name.isBlank()) {
                return null;
            }
            Map<String, Object> arguments = parseArguments(node.get("arguments"));
            if (arguments == null) {
                return null;
            }
            return new ToolCallResult(new ToolCall(name, arguments, id), id);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A parsed tool call with its optional ID.
     */
    public record ToolCallResult(ToolCall toolCall, String id) {
    }
}
