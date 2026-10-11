package com.paicli.subagent.verdict;

import java.util.List;

public record StructuredVerdict(
        Verdict verdict,
        Severity severity,
        double confidence,
        List<VerdictEvidence> evidence,
        RequiredAction requiredAction,
        String summary,
        boolean parsed
) {
    public StructuredVerdict {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static StructuredVerdict inconclusive(String reason) {
        return new StructuredVerdict(
                Verdict.INCONCLUSIVE,
                Severity.NONE,
                0.0d,
                List.of(new VerdictEvidence(EvidenceSourceType.PARSE_ERROR, null, null, reason)),
                RequiredAction.HUMAN_REVIEW,
                reason,
                false
        );
    }
}
