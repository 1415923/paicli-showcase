package com.paicli.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Explicit, immutable bounds for autonomous same-Task execution continuity.
 *
 * <p>No enabled production defaults are defined here. Callers must deliberately provide every
 * bound; the ordinary production construction path uses {@link #disabled()}.</p>
 */
public record TaskContinuityPolicy(
        boolean enabled,
        int maxAdditionalSegments,
        int maxPlanAttempts,
        int maxTotalProviderTurns,
        int maxTotalToolCalls,
        long maxTotalInputTokens,
        long maxTotalOutputTokens,
        long maxActiveElapsedMillis
) {
    public TaskContinuityPolicy {
        if (enabled) {
            requirePositive(maxAdditionalSegments, "maxAdditionalSegments");
            requirePositive(maxPlanAttempts, "maxPlanAttempts");
            requirePositive(maxTotalProviderTurns, "maxTotalProviderTurns");
            requirePositive(maxTotalToolCalls, "maxTotalToolCalls");
            requirePositive(maxTotalInputTokens, "maxTotalInputTokens");
            requirePositive(maxTotalOutputTokens, "maxTotalOutputTokens");
            requirePositive(maxActiveElapsedMillis, "maxActiveElapsedMillis");
        } else if (maxAdditionalSegments != 0 || maxPlanAttempts != 0
                || maxTotalProviderTurns != 0 || maxTotalToolCalls != 0
                || maxTotalInputTokens != 0 || maxTotalOutputTokens != 0
                || maxActiveElapsedMillis != 0) {
            throw new IllegalArgumentException("Disabled continuity policy must have zero bounds");
        }
    }

    public static TaskContinuityPolicy disabled() {
        return new TaskContinuityPolicy(false, 0, 0, 0, 0, 0, 0, 0);
    }

    /** Deterministic small-budget policy intended only for offline acceptance tests. */
    public static TaskContinuityPolicy acceptanceTestPolicy() {
        return new TaskContinuityPolicy(true, 2, 2, 8, 8, 16_384, 16_384, 30_000);
    }

    public String policyHash() {
        String canonical = enabled + "\n" + maxAdditionalSegments + "\n" + maxPlanAttempts
                + "\n" + maxTotalProviderTurns + "\n" + maxTotalToolCalls + "\n"
                + maxTotalInputTokens + "\n" + maxTotalOutputTokens + "\n"
                + maxActiveElapsedMillis;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void requirePositive(long value, String field) {
        if (value <= 0) throw new IllegalArgumentException(field + " must be positive");
    }
}
