package com.paicli.provider.telemetry;

/** Non-authority diagnostic correlation context. */
public record ProviderCallContext(
        String sessionId, String taskId, String runId, String generationId) {
    public static ProviderCallContext empty() {
        return new ProviderCallContext(null, null, null, null);
    }
}
