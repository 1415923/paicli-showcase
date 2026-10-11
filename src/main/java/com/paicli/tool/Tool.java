package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;

import java.util.Optional;

/**
 * A tool that can be invoked by the agent loop.
 */
public interface Tool {

    /** Unique tool name, e.g. "list_files". */
    String name();

    /** Human-readable description for the model. */
    String description();

    /** Returns the ToolDefinition (name + description + JSON Schema). */
    ToolDefinition toDefinition();

    /** Validate a call without producing external effects. */
    default ToolResult preflightCall(ToolCall call) { return ToolResult.success(""); }

    /** Project exact Plan authority from the same arguments accepted by preflight. */
    default Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
        return Optional.empty();
    }

    /**
     * Execute the tool call and return a result.
     * Implementations must validate parameters internally (fail-closed on missing args).
     */
    ToolResult execute(ToolCall call);
}
