package com.paicli.runtime;

/** Result and persisted excerpts from one verification command execution. */
public record VerificationResult(
        String verificationId,
        String runId,
        String taskId,
        int repairAttempt,
        String command,
        String status,
        Integer exitCode,
        long durationMs,
        String outputExcerpt,
        String errorExcerpt,
        String candidateDigestKind,
        String candidateDigest
) {
}
