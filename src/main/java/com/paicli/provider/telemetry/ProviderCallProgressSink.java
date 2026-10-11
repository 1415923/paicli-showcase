package com.paicli.provider.telemetry;

import java.util.Objects;

/** Bounded public progress seam; implementations receive no prompt or response content. */
public interface ProviderCallProgressSink {
    ProviderCallProgressSink NOOP = new ProviderCallProgressSink() { };

    default void started(String correlationId, ProviderCallContext context,
            String operation) { }

    default void elapsed(String correlationId, ProviderCallContext context,
            String operation, long elapsedSeconds) { }

    default void retrying(ProviderCallContext context, String operation, int attempt,
            String reason) { }

    /** Structured retry notification. The digest is a hash; no request content is exposed. */
    default void retrying(RetryProgress progress) {
        retrying(progress.context(),progress.operation(),progress.nextAttempt(),progress.reason());
    }

    default void restored(String logicalCallId,ProviderCallContext context,String operation,
            int successfulAttempt) { }

    default void exhausted(String logicalCallId,ProviderCallContext context,String operation,
            int attempts,String reason,String requestDigest) { }

    default void finished(String correlationId) { }

    record RetryProgress(String logicalCallId,ProviderCallContext context,String operation,
            int nextAttempt,int retryCount,String reason,long backoffMillis,
            String requestDigest) {
        public RetryProgress {
            Objects.requireNonNull(logicalCallId,"logicalCallId");
            context=context==null?ProviderCallContext.empty():context;
            Objects.requireNonNull(operation,"operation");
            Objects.requireNonNull(reason,"reason");
            if(nextAttempt<2||nextAttempt>3||retryCount!=nextAttempt-1||backoffMillis<0)
                throw new IllegalArgumentException("invalid bounded retry progress");
            if(requestDigest==null||!requestDigest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid request digest");
        }
    }
}
