package com.paicli.mcp.sanitize;

public record McpSanitizedOutput(String content, String contentTypeSummary, String originalHash,
                                 String sanitizedHash, boolean truncated, boolean redacted,
                                 int omittedBinaryCount) { }
