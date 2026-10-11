package com.paicli.mcp.recovery;

public record McpSessionRecoveryReport(String serverId, String sessionIdSummary, String persistedStatus,
                                       String liveStatus, String manifestDrift, boolean processAlive,
                                       String catalogStatus, String recommendation) {
    static String shortId(String value) { return value == null ? "none" : value.substring(0, Math.min(12, value.length())); }
}