package com.paicli.mcp.recovery;

public record McpToolCallRecoveryReport(String callIdSummary, String toolName, String persistedStatus,
                                        OutcomeCertainty outcomeCertainty, String approvalFact,
                                        String errorCategory, boolean retryAutomatically) {
    public enum OutcomeCertainty { CONFIRMED_NOT_SENT, CONFIRMED_TERMINAL, UNKNOWN_EXTERNAL_OUTCOME }
}