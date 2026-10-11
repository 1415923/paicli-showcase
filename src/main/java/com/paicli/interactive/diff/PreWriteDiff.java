package com.paicli.interactive.diff;

import java.util.List;

public record PreWriteDiff(
        String relativePath,
        String beforeHash,
        String afterHash,
        int additions,
        int deletions,
        List<String> boundedLines,
        boolean truncated,
        boolean redacted,
        boolean existingFile,
        String conflictingRelativePath) {
    public PreWriteDiff(String relativePath, String beforeHash, String afterHash,
            int additions, int deletions, List<String> boundedLines,
            boolean truncated, boolean redacted, boolean existingFile) {
        this(relativePath, beforeHash, afterHash, additions, deletions,
                boundedLines, truncated, redacted, existingFile, null);
    }
    public PreWriteDiff {
        boundedLines = List.copyOf(boundedLines);
        if (boundedLines.size() > 400 || relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("invalid bounded pre-write diff");
        }
        if (conflictingRelativePath != null && conflictingRelativePath.length() > 512) {
            throw new IllegalArgumentException("invalid conflicting path");
        }
    }
}
