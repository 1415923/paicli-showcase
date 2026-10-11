package com.paicli.codeintelligence;

import java.util.List;

public record CallSiteResult(
        ResolutionStatus status,
        ConfidenceLevel confidence,
        List<MethodSymbol> targetCandidates,
        List<CallSiteCandidate> callSites,
        List<String> issues,
        String message
) {
    public int resultCount() {
        return callSites.size();
    }

    public boolean ambiguous() {
        return status == ResolutionStatus.AMBIGUOUS
                || status == ResolutionStatus.MULTIPLE_CANDIDATES
                || callSites.stream().anyMatch(candidate ->
                candidate.resolutionStatus() == ResolutionStatus.AMBIGUOUS
                        || candidate.resolutionStatus() == ResolutionStatus.MULTIPLE_CANDIDATES);
    }
}
