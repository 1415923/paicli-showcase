package com.paicli.tool;

import java.util.Map;

/**
 * Immutable result from tool execution.
 *
 * @param success  whether the tool executed successfully
 * @param output   textual output (null on error)
 * @param error    error message (null on success)
 * @param metadata optional key-value metadata (e.g. file count, match count)
 */
public record ToolResult(boolean success, String output, String error, Map<String, Object> metadata) {
    public ToolResult {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ToolResult success(String output) {
        return new ToolResult(true, output, null, Map.of());
    }

    public static ToolResult success(String output, Map<String, Object> metadata) {
        return new ToolResult(true, output, null, metadata);
    }

    public static ToolResult error(String error) {
        return new ToolResult(false, null, error, Map.of());
    }
}
