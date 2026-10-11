package com.paicli.demo.verification;

import com.paicli.demo.scenario.CoreDemoContractHash;
import java.util.List;
import java.util.Objects;

public record CoreDemoVerificationEvidence(
        String phase, String verificationId, String commandPolicyHash,
        String sourceTreeHash, int tests, int failures, int errors, int skipped,
        List<FailedTest> failedTests, String assertionCategory,
        String boundedAssertionExcerpt, String stdoutHash, String stderrHash,
        boolean timedOut, boolean processCleanupComplete, String evidenceHash) {
    public CoreDemoVerificationEvidence {
        failedTests = List.copyOf(failedTests == null ? List.of() : failedTests);
        if (tests < 0 || failures < 0 || errors < 0 || skipped < 0
                || failures + errors > tests || failedTests.size() > 8) {
            throw new IllegalArgumentException("Invalid verification counts");
        }
        Objects.requireNonNull(phase);
        Objects.requireNonNull(verificationId);
        requireSha(commandPolicyHash); requireSha(sourceTreeHash);
        requireSha(stdoutHash); requireSha(stderrHash); requireSha(evidenceHash);
        assertionCategory = bounded(assertionCategory, 128);
        boundedAssertionExcerpt = bounded(boundedAssertionExcerpt, 1024);
    }

    public String canonicalFacts() {
        String failed = failedTests.stream()
                .sorted(java.util.Comparator.comparing(FailedTest::className)
                        .thenComparing(FailedTest::methodName))
                .map(test -> test.className() + "#" + test.methodName())
                .collect(java.util.stream.Collectors.joining(","));
        return String.join("\n", "phase=" + phase,
                "commandPolicyHash=" + commandPolicyHash,
                "sourceTreeHash=" + sourceTreeHash, "tests=" + tests,
                "failures=" + failures, "errors=" + errors, "skipped=" + skipped,
                "failedTests=" + failed,
                "assertionCategory=" + Objects.toString(assertionCategory, ""),
                "stdoutHash=" + stdoutHash, "stderrHash=" + stderrHash,
                "timedOut=" + timedOut,
                "processCleanupComplete=" + processCleanupComplete);
    }

    public static String hashCanonical(String canonicalFacts) {
        return CoreDemoContractHash.sha256(canonicalFacts);
    }

    private static void requireSha(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected SHA-256");
    }

    private static String bounded(String value, int max) {
        if (value == null) return null;
        String clean = value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        return clean.substring(0, Math.min(max, clean.length()));
    }

    public record FailedTest(String className, String methodName) {
        public FailedTest {
            if (className == null || className.isBlank() || className.length() > 256
                    || methodName == null || methodName.isBlank() || methodName.length() > 256)
                throw new IllegalArgumentException("Invalid failed test identity");
        }
    }
}