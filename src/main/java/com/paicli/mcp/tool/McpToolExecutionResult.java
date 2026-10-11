package com.paicli.mcp.tool;

import com.paicli.mcp.sanitize.McpSanitizedOutput;

public record McpToolExecutionResult(String callId, String status, McpSanitizedOutput output, String errorCategory) { }
