package com.paicli.demo.baseline;
public record CoreDemoBaselineResult(Status status, Integer exitCode, long durationMs,
        int tests, int failures, int errors, int skipped, String stdoutHash, String stderrHash,
        String boundedExcerpt, boolean timedOut, boolean processCleaned, boolean buildResidueCleaned, String sourceTreeHash) {
    public enum Status { BASELINE_PASSED, DEMO_BASELINE_INVALID, BASELINE_CLEANUP_FAILED, BASELINE_ERROR }
}
