package com.paicli.interactive.view;

import java.time.Instant;

public record ProviderView(
        String provider, String model, String source,
        AvailabilityState availabilityState,
        ConfigurationState configurationState,
        ReadinessState readinessState,
        LastCallState lastCallState,
        UsageState usageState,
        String lastOperationKind, String lastOutcome, String lastErrorCategory,
        Long lastLatencyMs, Integer inputTokens, Integer outputTokens,
        Integer totalTokens, int sessionCallCount, int taskCallCount,
        Long sessionInputTokens, Long sessionOutputTokens, Long sessionTotalTokens,
        Long taskInputTokens, Long taskOutputTokens, Long taskTotalTokens,
        Instant lastObservedAt, String diagnosticRef,
        String thinkingMode, String reasoningEffort,
        Instant healthObservedAt) {
    public ProviderView(String provider, String model, String source,
            AvailabilityState availabilityState, ConfigurationState configurationState,
            ReadinessState readinessState, LastCallState lastCallState, UsageState usageState,
            String lastOperationKind, String lastOutcome, String lastErrorCategory,
            Long lastLatencyMs, Integer inputTokens, Integer outputTokens,
            Integer totalTokens, int sessionCallCount, int taskCallCount,
            Long sessionInputTokens, Long sessionOutputTokens, Long sessionTotalTokens,
            Long taskInputTokens, Long taskOutputTokens, Long taskTotalTokens,
            Instant lastObservedAt, String diagnosticRef) {
        this(provider,model,source,availabilityState,configurationState,readinessState,
                lastCallState,usageState,lastOperationKind,lastOutcome,lastErrorCategory,
                lastLatencyMs,inputTokens,outputTokens,totalTokens,sessionCallCount,taskCallCount,
                sessionInputTokens,sessionOutputTokens,sessionTotalTokens,taskInputTokens,
                taskOutputTokens,taskTotalTokens,lastObservedAt,diagnosticRef,null,null,null);
    }
    public ProviderView(String provider, String model, String source,
            AvailabilityState availabilityState, ConfigurationState configurationState,
            ReadinessState readinessState, LastCallState lastCallState, UsageState usageState,
            String lastOperationKind, String lastOutcome, String lastErrorCategory,
            Long lastLatencyMs, Integer inputTokens, Integer outputTokens,
            Integer totalTokens, int sessionCallCount, int taskCallCount,
            Long sessionInputTokens, Long sessionOutputTokens, Long sessionTotalTokens,
            Long taskInputTokens, Long taskOutputTokens, Long taskTotalTokens,
            Instant lastObservedAt, String diagnosticRef, String thinkingMode,
            String reasoningEffort) {
        this(provider,model,source,availabilityState,configurationState,readinessState,
                lastCallState,usageState,lastOperationKind,lastOutcome,lastErrorCategory,
                lastLatencyMs,inputTokens,outputTokens,totalTokens,sessionCallCount,taskCallCount,
                sessionInputTokens,sessionOutputTokens,sessionTotalTokens,taskInputTokens,
                taskOutputTokens,taskTotalTokens,lastObservedAt,diagnosticRef,thinkingMode,
                reasoningEffort,null);
    }
    public enum AvailabilityState { AVAILABLE, UNAVAILABLE }
    public enum ConfigurationState { CONFIGURED, NOT_CONFIGURED }
    public enum ReadinessState { READINESS_UNKNOWN, HEALTHY, STALE, DEGRADED, UNAVAILABLE }
    public enum LastCallState {
        LAST_CALL_SUCCEEDED, LAST_CALL_FAILED, LAST_CALL_CANCELLED,
        LAST_CALL_INCOMPLETE, UNKNOWN
    }
    public enum UsageState { REPORTED, PARTIAL, USAGE_NOT_EXPOSED }
}
