package com.paicli.mcp.transport;

public record McpStderrSummary(String hash, String excerpt, boolean truncated, boolean redacted, String readerFinding) {}
