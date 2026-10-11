package com.paicli.subagent.verdict;

public record VerdictExtractionResult(
        StructuredVerdict verdict,
        String strategyName,
        VerdictExtractionStatus status,
        String errorExcerpt
) {
    private static final int MAX_ERROR_CHARS = 240;

    public VerdictExtractionResult {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required");
        }
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        errorExcerpt = truncate(errorExcerpt);
    }

    public static VerdictExtractionResult from(String strategyName, StructuredVerdict verdict) {
        if (verdict.parsed()) {
            return new VerdictExtractionResult(
                    verdict, strategyName, VerdictExtractionStatus.PARSED, null);
        }
        return new VerdictExtractionResult(
                verdict, strategyName, VerdictExtractionStatus.INCONCLUSIVE, verdict.summary());
    }

    public VerdictExtractionResult asFallback(String fallbackReason) {
        if (status != VerdictExtractionStatus.PARSED) {
            return this;
        }
        return new VerdictExtractionResult(
                verdict, strategyName, VerdictExtractionStatus.FALLBACK_PARSED, fallbackReason);
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_CHARS) {
            return value;
        }
        return value.substring(0, MAX_ERROR_CHARS) + "...[truncated]";
    }
}
