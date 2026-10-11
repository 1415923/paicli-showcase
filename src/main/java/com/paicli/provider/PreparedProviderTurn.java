package com.paicli.provider;

import com.paicli.model.ModelResponse;

import java.util.Objects;

/** Immutable, redacted Provider request prepared once for one logical turn. */
public final class PreparedProviderTurn {
    private final String requestDigest;
    private final Attempt attempt;
    private final RequestMetrics metrics;

    public PreparedProviderTurn(String requestDigest, Attempt attempt) {
        this(requestDigest, null, attempt);
    }

    public PreparedProviderTurn(String requestDigest, RequestMetrics metrics, Attempt attempt) {
        if (requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestDigest must be a SHA-256 hex digest");
        }
        this.requestDigest = requestDigest;
        this.attempt = Objects.requireNonNull(attempt, "attempt");
        this.metrics = metrics;
    }

    /** Null means unavailable (for example a structural digest), never zero wire bytes. */
    public RequestMetrics metrics() { return metrics; }
    public record RequestMetrics(long utf8Bytes, int messageCount, int toolCount,
            int maxOutputTokens, String model, String thinkingMode, OutputRequirement requirement) {
        public RequestMetrics {
            if (utf8Bytes < 1 || messageCount < 0 || toolCount < 0 || maxOutputTokens < 1)
                throw new IllegalArgumentException("INVALID_REQUEST_METRICS");
            Objects.requireNonNull(requirement);
        }
    }

    public String requestDigest() {
        return requestDigest;
    }

    public ModelResponse execute() {
        return Objects.requireNonNull(attempt.execute(), "Provider attempt returned null");
    }

    @Override
    public String toString() {
        return "PreparedProviderTurn[requestDigest=" + requestDigest.substring(0, 12)
                + "..., body=<redacted>]";
    }

    @FunctionalInterface
    public interface Attempt {
        ModelResponse execute();
    }
}
