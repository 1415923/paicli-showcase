package com.paicli.codeindex;

import java.util.List;

public record CodeIndexResult(
        int discoveredCount,
        int indexedCount,
        int skippedUnchangedCount,
        int errorCount,
        int deletedCount,
        List<CodeIndexIssue> issues
) {

    public record CodeIndexIssue(String relativePath, String message) {
    }
}
