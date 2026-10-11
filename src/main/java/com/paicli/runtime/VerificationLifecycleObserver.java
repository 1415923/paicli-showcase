package com.paicli.runtime;

/** Optional post-persistence observer for verification lifecycle facts. */
public interface VerificationLifecycleObserver {
    VerificationLifecycleObserver NOOP = new VerificationLifecycleObserver() { };

    default void started(String verificationId, String runId, String taskId,
                         int repairAttempt, String policyLabel) { }

    default void completed(VerificationResult result) { }
}
