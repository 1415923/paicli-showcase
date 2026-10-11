package com.paicli.provider.telemetry;

import com.paicli.model.ModelResponse;
import com.paicli.provider.ProviderRetrySignal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** One shared bounded budget for every retry cause in a logical Provider turn. */
public final class ProviderRetryPolicy {
    public static final int MAX_ATTEMPTS = 3;
    private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(10);

    private final Sleeper sleeper;
    private final Jitter jitter;

    public ProviderRetryPolicy() {
        this(duration -> Thread.sleep(duration.toMillis()),
                upper -> ThreadLocalRandom.current().nextLong(upper + 1));
    }

    ProviderRetryPolicy(Sleeper sleeper, Jitter jitter) {
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.jitter = Objects.requireNonNull(jitter, "jitter");
    }

    Decision decide(ModelResponse response, ProviderRetrySignal signal, int attempt) {
        if (attempt >= MAX_ATTEMPTS || response == null
                || response.type() != ModelResponse.ResponseType.PROVIDER_ERROR) {
            return Decision.stop();
        }
        ModelResponse.ProviderErrorKind kind = response.providerErrorKind();
        ProviderRetrySignal.Reason reason = signal == null ? fallbackReason(kind) : signal.reason();
        boolean retryable = signal == null ? fallbackRetryable(kind) : signal.retryable();
        if (!retryable || reason == ProviderRetrySignal.Reason.NON_RETRYABLE
                ||!allowedPair(kind,reason)) {
            return Decision.stop();
        }
        Duration delay = signal != null && signal.retryAfter() != null
                ? min(signal.retryAfter(), MAX_RETRY_AFTER) : defaultDelay(attempt);
        return new Decision(true, reason, delay,
                signal == null ? null : signal.httpCategory());
    }

    void sleep(Duration delay) throws InterruptedException {
        sleeper.sleep(delay);
    }

    private Duration defaultDelay(int failedAttempt) {
        long base = failedAttempt == 1 ? 1_000L : 3_000L;
        long upper = failedAttempt == 1 ? 250L : 500L;
        return Duration.ofMillis(base + Math.max(0L, Math.min(upper, jitter.millis(upper))));
    }

    private static boolean fallbackRetryable(ModelResponse.ProviderErrorKind kind) {
        return switch (kind) {
            case PROVIDER_CONNECTION_FAILURE, PROVIDER_TIMEOUT,
                    PROVIDER_TRANSPORT_INTERRUPTED, PROVIDER_RATE_LIMITED,
                    PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE -> true;
            default -> false;
        };
    }

    private static ProviderRetrySignal.Reason fallbackReason(
            ModelResponse.ProviderErrorKind kind) {
        return switch (kind) {
            case PROVIDER_CONNECTION_FAILURE ->
                    ProviderRetrySignal.Reason.TRANSPORT_CONNECTION_RESET;
            case PROVIDER_TIMEOUT -> ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT;
            case PROVIDER_TRANSPORT_INTERRUPTED ->
                    ProviderRetrySignal.Reason.TRANSPORT_INTERRUPTED;
            case PROVIDER_RATE_LIMITED -> ProviderRetrySignal.Reason.HTTP_429;
            case PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE ->
                    ProviderRetrySignal.Reason.PRIVATE_CONTINUATION_INCOMPLETE;
            default -> ProviderRetrySignal.Reason.NON_RETRYABLE;
        };
    }

    private static boolean allowedPair(ModelResponse.ProviderErrorKind kind,
            ProviderRetrySignal.Reason reason){
        return switch(kind){
            case PROVIDER_CONNECTION_FAILURE -> reason==ProviderRetrySignal.Reason
                    .TRANSPORT_CONNECTION_RESET||reason==ProviderRetrySignal.Reason
                    .TRANSPORT_INTERRUPTED;
            case PROVIDER_TIMEOUT -> reason==ProviderRetrySignal.Reason.TRANSPORT_CONNECT_TIMEOUT
                    ||reason==ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT;
            case PROVIDER_TRANSPORT_INTERRUPTED -> switch(reason){
                case TRANSPORT_TLS_EOF,TRANSPORT_CONNECTION_RESET,TRANSPORT_INTERRUPTED -> true;
                default -> false;
            };
            case PROVIDER_RATE_LIMITED -> reason==ProviderRetrySignal.Reason.HTTP_429;
            case PROVIDER_SERVER_FAILURE -> switch(reason){
                case HTTP_502,HTTP_503,HTTP_504 -> true;
                default -> false;
            };
            case PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE ->
                    reason==ProviderRetrySignal.Reason.PRIVATE_CONTINUATION_INCOMPLETE;
            default -> false;
        };
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    record Decision(boolean retry, ProviderRetrySignal.Reason reason, Duration delay,
            String httpCategory) {
        private static Decision stop() {
            return new Decision(false, ProviderRetrySignal.Reason.NON_RETRYABLE,
                    Duration.ZERO, null);
        }
    }

    @FunctionalInterface
    interface Sleeper { void sleep(Duration duration) throws InterruptedException; }

    @FunctionalInterface
    interface Jitter { long millis(long inclusiveUpperBound); }
}
