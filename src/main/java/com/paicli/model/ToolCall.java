package com.paicli.model;

import java.util.Map;

/**
 * Represents a request from the model to invoke a tool.
 *
 * @param name      tool name, e.g. "list_files"
 * @param arguments tool parameters as key-value pairs
 */
public record ToolCall(String name, Map<String, Object> arguments, String providerCallId) {
    public ToolCall(String name, Map<String, Object> arguments) {
        this(name, arguments, null);
    }
}
