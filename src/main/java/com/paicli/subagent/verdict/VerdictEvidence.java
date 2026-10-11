package com.paicli.subagent.verdict;

public record VerdictEvidence(
        EvidenceSourceType sourceType,
        String file,
        Integer line,
        String message
) {
}
