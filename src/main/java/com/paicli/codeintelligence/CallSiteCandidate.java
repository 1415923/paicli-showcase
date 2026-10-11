package com.paicli.codeintelligence;

import java.util.List;

public record CallSiteCandidate(
        String callerFilePath,
        String callerClass,
        String callerMethod,
        String callerKind,
        String callerSignature,
        String callExpressionText,
        int callLine,
        int argumentCount,
        ResolutionStatus resolutionStatus,
        ConfidenceLevel confidence,
        List<CallSiteEvidence> evidence,
        List<MethodSymbol> targetCandidates
) {
}
