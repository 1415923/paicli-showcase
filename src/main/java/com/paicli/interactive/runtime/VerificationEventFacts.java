package com.paicli.interactive.runtime;

import com.paicli.runtime.VerificationResult;
import com.paicli.state.VerificationStore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

record VerificationEventFacts(
        String category, int tests, int failures, int errors, int skipped,
        boolean timedOut, boolean cleanupComplete, String boundedSummary) {
    private static final Pattern MAVEN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_SUMMARY = 512;

    static VerificationEventFacts from(VerificationResult result) {
        int tests = -1, failures = -1, errors = -1, skipped = -1;
        String combined = safe(result.outputExcerpt()) + "\n" + safe(result.errorExcerpt());
        Matcher matcher = MAVEN.matcher(combined);
        while (matcher.find()) {
            tests = parse(matcher.group(1));
            failures = parse(matcher.group(2));
            errors = parse(matcher.group(3));
            skipped = parse(matcher.group(4));
        }
        String category = switch (result.status()) {
            case VerificationStore.STATUS_PASSED -> "PASSED";
            case VerificationStore.STATUS_SKIPPED -> "SKIPPED";
            case VerificationStore.STATUS_TIMED_OUT -> "TIMED_OUT";
            case VerificationStore.STATUS_ERROR -> "VERIFIER_ERROR";
            default -> "TEST_FAILURE";
        };
        String summary = firstUsefulLine(result.errorExcerpt(), result.outputExcerpt());
        if (summary == null) {
            summary = result.status() + (result.exitCode() == null
                    ? "" : " (exit " + result.exitCode() + ")");
        }
        return new VerificationEventFacts(category, tests, failures, errors, skipped,
                VerificationStore.STATUS_TIMED_OUT.equals(result.status()),
                true, bound(summary));
    }

    boolean passed() {
        return VerificationStore.STATUS_PASSED.equals(category);
    }

    boolean skippedVerification() { return "SKIPPED".equals(category); }

    private static int parse(String value) {
        try {
            return Math.min(Integer.parseInt(value), 1_000_000);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String firstUsefulLine(String first, String second) {
        for (String value : new String[]{first, second}) {
            if (value == null) continue;
            for (String line : value.split("\\R")) {
                String clean = line.strip();
                if (!clean.isEmpty()) return clean;
            }
        }
        return null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String bound(String value) {
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        return clean.length() <= MAX_SUMMARY
                ? clean : clean.substring(0, MAX_SUMMARY - 15) + " [truncated]";
    }
}
