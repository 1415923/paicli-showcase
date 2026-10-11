package com.paicli.provider;

import java.time.Duration;

/** Bounded, content-free retry metadata emitted by a Provider adapter attempt. */
public record ProviderRetrySignal(Reason reason, boolean retryable, Duration retryAfter,
        String httpCategory) {
    public ProviderRetrySignal {
        java.util.Objects.requireNonNull(reason, "reason");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must not be negative");
        }
        if (httpCategory != null
                && !httpCategory.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("unsafe HTTP category");
        }
    }

    public enum Reason {
        TRANSPORT_CONNECT_TIMEOUT,
        TRANSPORT_TLS_EOF,
        TRANSPORT_CONNECTION_RESET,
        TRANSPORT_READ_TIMEOUT,
        TRANSPORT_INTERRUPTED,
        HTTP_429,
        HTTP_502,
        HTTP_503,
        HTTP_504,
        PRIVATE_CONTINUATION_INCOMPLETE,
        NON_RETRYABLE
    }
}
