package com.paicli.subagent.verdict;

public record StaticAnalysisEvidence(
        EvidenceSourceType sourceType,
        String file,
        Integer line,
        String message,
        Severity severity,
        String patternId
) {
}
