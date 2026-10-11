package com.paicli.provider.telemetry;

import com.paicli.provider.ProviderResponseEnvelope;

import java.util.Objects;

/** Allowlisted normalized Provider response facts; never raw Provider content. */
public record ProviderCallObservation(
        Integer inputTokens, Integer outputTokens, Integer totalTokens,
        Integer cacheReadTokens, Integer cacheWriteTokens, Integer reasoningTokens,
        UsageSource usageSource, UsageCoverage usageCoverage,
        Integer toolCallCount,
        ProviderResponseEnvelope.ToolCallCardinality responseCardinality,
        String finishReason, String safeRequestId,
        ResponseIdentityProvenance responseIdentityProvenance, String errorCategory,
        boolean cancelled) {

    public enum UsageSource { NOT_EXPOSED, PROVIDER_REPORTED, DERIVED }
    public enum UsageCoverage { NONE, PARTIAL, COMPLETE }
    public enum ResponseIdentityProvenance {
        PROVIDER_RETURNED,
        PROVIDER_NOT_RETURNED,
        ADAPTER_NOT_EXPOSED
    }

    /** Compatibility constructor for existing allowlisted observations. */
    public ProviderCallObservation(Integer inputTokens, Integer outputTokens,
            Integer totalTokens, Integer cacheReadTokens, Integer cacheWriteTokens,
            Integer reasoningTokens, UsageSource usageSource, UsageCoverage usageCoverage,
            Integer toolCallCount,
            ProviderResponseEnvelope.ToolCallCardinality responseCardinality,
            String finishReason, String safeRequestId, String errorCategory,
            boolean cancelled) {
        this(inputTokens, outputTokens, totalTokens, cacheReadTokens, cacheWriteTokens,
                reasoningTokens, usageSource, usageCoverage, toolCallCount,
                responseCardinality, finishReason, safeRequestId,
                safeRequestId == null ? ResponseIdentityProvenance.ADAPTER_NOT_EXPOSED
                        : ResponseIdentityProvenance.PROVIDER_RETURNED,
                errorCategory, cancelled);
    }

    public ProviderCallObservation {
        Objects.requireNonNull(usageSource, "usageSource");
        Objects.requireNonNull(usageCoverage, "usageCoverage");
        Objects.requireNonNull(responseIdentityProvenance,
                "responseIdentityProvenance");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(totalTokens, "totalTokens");
        requireNonNegative(cacheReadTokens, "cacheReadTokens");
        requireNonNegative(cacheWriteTokens, "cacheWriteTokens");
        requireNonNegative(reasoningTokens, "reasoningTokens");
        requireNonNegative(toolCallCount, "toolCallCount");
        boolean coreAny=inputTokens!=null||outputTokens!=null||totalTokens!=null;
        boolean coreAll=inputTokens!=null&&outputTokens!=null&&totalTokens!=null;
        boolean allNull=!coreAny&&cacheReadTokens==null&&cacheWriteTokens==null
                &&reasoningTokens==null;
        boolean usageValid=usageSource==UsageSource.NOT_EXPOSED
                    &&usageCoverage==UsageCoverage.NONE&&allNull
                ||usageSource==UsageSource.PROVIDER_REPORTED
                    &&usageCoverage==UsageCoverage.PARTIAL&&coreAny&&!coreAll
                ||usageSource==UsageSource.PROVIDER_REPORTED
                    &&usageCoverage==UsageCoverage.COMPLETE&&coreAll
                ||usageSource==UsageSource.DERIVED&&usageCoverage==UsageCoverage.COMPLETE
                    &&coreAll&&(long)totalTokens==(long)inputTokens+outputTokens;
        if(!usageValid)throw new IllegalArgumentException("invalid usage source/coverage");
        finishReason = boundedEnumLike(finishReason, 64);
        safeRequestId = boundedEnumLike(safeRequestId, 128);
        if (responseIdentityProvenance != ResponseIdentityProvenance.PROVIDER_RETURNED
                && safeRequestId != null) {
            throw new IllegalArgumentException(
                    "response identity cannot exist when Provider did not return it");
        }
        errorCategory = boundedEnumLike(errorCategory, 64);
    }

    public static ProviderCallObservation notExposed() {
        return new ProviderCallObservation(null, null, null, null, null, null,
                UsageSource.NOT_EXPOSED, UsageCoverage.NONE, null, null,
                null, null, ResponseIdentityProvenance.ADAPTER_NOT_EXPOSED,
                null, false);
    }

    public ProviderCallObservation withResponseIdentity(String responseId,
            ResponseIdentityProvenance provenance) {
        return new ProviderCallObservation(inputTokens, outputTokens, totalTokens,
                cacheReadTokens, cacheWriteTokens, reasoningTokens, usageSource,
                usageCoverage, toolCallCount, responseCardinality, finishReason,
                responseId, provenance, errorCategory, cancelled);
    }

    public ProviderCallObservation withError(String category, boolean wasCancelled) {
        return new ProviderCallObservation(inputTokens, outputTokens, totalTokens,
                cacheReadTokens, cacheWriteTokens, reasoningTokens, usageSource,
                usageCoverage, toolCallCount, responseCardinality, finishReason,
                safeRequestId, responseIdentityProvenance, category, wasCancelled);
    }

    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static String boundedEnumLike(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= max && clean.matches("[A-Za-z0-9._:-]+") ? clean : null;
    }
}
