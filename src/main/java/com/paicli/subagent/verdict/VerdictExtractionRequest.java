package com.paicli.subagent.verdict;

import java.util.List;

public record VerdictExtractionRequest(
        String rawOutput,
        VerdictExtractionMode mode,
        List<StaticAnalysisEvidence> staticEvidence
) {
    public VerdictExtractionRequest {
        mode = mode == null ? VerdictExtractionMode.AUTO : mode;
        staticEvidence = staticEvidence == null ? List.of() : List.copyOf(staticEvidence);
    }

    public static VerdictExtractionRequest modelOutput(String rawOutput) {
        return new VerdictExtractionRequest(rawOutput, VerdictExtractionMode.AUTO, List.of());
    }

    public static VerdictExtractionRequest staticEvidence(List<StaticAnalysisEvidence> evidence) {
        return new VerdictExtractionRequest(null, VerdictExtractionMode.STATIC_ANALYSIS, evidence);
    }
}
