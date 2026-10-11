package com.paicli.demo.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

public record CoreDemoRunResult(String runId, String scenarioId, String status,
        CoreDemoExitCode exitCode, String artifactLocation, boolean diagnosticsRetained,
        String errorCategory, String boundedErrorExcerpt) {
    public CoreDemoRunResult {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(exitCode);
        if (artifactLocation != null && (artifactLocation.contains(":\\")
                || artifactLocation.startsWith("/") || artifactLocation.startsWith("\\\\"))) {
            throw new IllegalArgumentException("Artifact location must not be absolute");
        }
        errorCategory = bound(errorCategory, 128);
        boundedErrorExcerpt = bound(boundedErrorExcerpt, 2048);
    }
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\[^\\s]+|/(?:[^\\s/]+/)+[^\\s]*)");
    private static String bound(String value, int max) {
        if (value == null) return null;
        String clean = value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        clean = ABSOLUTE_PATH.matcher(clean).replaceAll("[ABSOLUTE_PATH_REDACTED]");
        return clean.substring(0, Math.min(max, clean.length()));
    }
}
