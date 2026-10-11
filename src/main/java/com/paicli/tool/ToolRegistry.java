package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;

import java.util.*;

/**
 * Registry for tools. Tools are registered by name; duplicate names are rejected.
 * Unknown tool invocations return an error result (fail-closed for the agent loop).
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();
    private final com.paicli.mcp.schema.McpInputArgumentsValidator argumentsValidator =
            new com.paicli.mcp.schema.McpInputArgumentsValidator(64 * 1024);

    /**
     * Register a tool.
     *
     * @throws IllegalArgumentException if a tool with the same name is already registered
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        String name = tool.name();
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("Tool already registered: " + name);
        }
        tools.put(name, tool);
    }

    /** Look up a tool by name. Returns empty if not found. */
    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Invoke a tool by ToolCall. Returns error ToolResult if the tool is unknown.
     */
    public ToolResult invoke(ToolCall call) {
        if (call == null) {
            return ToolResult.error("ToolCall must not be null");
        }
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name());
        }
        return tool.execute(call);
    }

    /** Whole-batch admission check: registration, JSON Schema, then tool path policy. */
    public PreflightResult preflight(ToolCall call) {
        if(call==null)return PreflightResult.rejected("TOOL_CALL_MISSING","ToolCall is required",
                PreflightFailureKind.MODEL_CORRECTABLE);
        Tool tool=tools.get(call.name());
        if(tool==null)return PreflightResult.rejected("TOOL_NOT_REGISTERED",
                "Unknown tool: "+call.name(),PreflightFailureKind.MODEL_CORRECTABLE);
        try{
            argumentsValidator.validate(json.valueToTree(
                    call.arguments()==null?Map.of():call.arguments()),
                    json.writeValueAsString(tool.toDefinition().parameterSchema()));
        }catch(com.paicli.mcp.schema.McpArgumentValidationException error){
            return PreflightResult.rejected("TOOL_ARGUMENT_SCHEMA_"+error.category(),
                    error.getMessage(),PreflightFailureKind.MODEL_CORRECTABLE);
        }catch(Exception error){
            return PreflightResult.rejected("TOOL_ARGUMENT_SCHEMA_INVALID",
                    "Tool arguments could not be validated",PreflightFailureKind.MODEL_CORRECTABLE);
        }
        ToolResult result=tool.preflightCall(call);
        if(!result.success())return PreflightResult.rejected(
                "TOOL_PREFLIGHT_REJECTED",result.error(),PreflightFailureKind.POLICY_DENIED);
        try{
            return PreflightResult.allow(tool.authorityProjection(call).orElse(null));
        }catch(RuntimeException error){
            return PreflightResult.rejected("TOOL_AUTHORITY_PROJECTION_FAILED",
                    "Tool authority could not be projected",PreflightFailureKind.INTEGRITY_FAILURE);
        }
    }

    /** Return all registered ToolDefinitions for the model. */
    public List<ToolDefinition> allDefinitions() {
        return tools.values().stream()
                .map(Tool::toDefinition)
                .toList();
    }

    /** Number of registered tools. */
    public int size() {
        return tools.size();
    }

    /** Returns a stable snapshot without exposing the mutable registry map. */
    public List<Tool> allTools() {
        return List.copyOf(tools.values());
    }

    public enum PreflightFailureKind {
        MODEL_CORRECTABLE, APPROVAL_REQUIRED, POLICY_DENIED, INTEGRITY_FAILURE
    }

    public record PreflightResult(boolean accepted,String code,String message,
            ToolAuthorityProjection authorityProjection,PreflightFailureKind failureKind){
        public static PreflightResult allow(){return allow(null);}
        public static PreflightResult allow(ToolAuthorityProjection projection){
            return new PreflightResult(true,null,null,projection,null);
        }
        public static PreflightResult rejected(String code,String message){
            return rejected(code,message,PreflightFailureKind.POLICY_DENIED);
        }
        public static PreflightResult rejected(String code,String message,
                PreflightFailureKind kind){
            return new PreflightResult(false,code,message,null,Objects.requireNonNull(kind));
        }
    }
}
