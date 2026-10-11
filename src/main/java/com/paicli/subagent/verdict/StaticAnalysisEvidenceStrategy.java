package com.paicli.subagent.verdict;

import java.util.Comparator;
import java.util.List;

public final class StaticAnalysisEvidenceStrategy implements VerdictExtractionStrategy {

    public static final String NAME = "static-analysis-evidence";
    private static final int MAX_SUMMARY_CHARS = 1_000;
    private static final int MAX_MESSAGE_CHARS = 1_000;

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(VerdictExtractionRequest request) {
        return request.mode() == VerdictExtractionMode.STATIC_ANALYSIS;
    }

    @Override
    public VerdictExtractionResult extract(VerdictExtractionRequest request) {
        if (request.staticEvidence().isEmpty()) {
            return VerdictExtractionResult.from(NAME, StructuredVerdict.inconclusive(
                    "static analysis produced no evidence; absence is not proof of PASS"));
        }
        if (request.staticEvidence().stream().anyMatch(this::invalidEvidence)) {
            return VerdictExtractionResult.from(NAME, StructuredVerdict.inconclusive(
                    "parse_error: static evidence must use a Reviewer source_type and severity"));
        }

        List<VerdictEvidence> evidence = request.staticEvidence().stream()
                .map(item -> new VerdictEvidence(
                        item.sourceType(), item.file(), item.line(),
                        truncate(withPatternId(item), MAX_MESSAGE_CHARS)))
                .toList();
        Severity maximum = request.staticEvidence().stream()
                .map(StaticAnalysisEvidence::severity)
                .max(Comparator.comparingInt(Enum::ordinal))
                .orElse(Severity.NONE);

        StructuredVerdict verdict;
        if (maximum == Severity.HIGH || maximum == Severity.CRITICAL) {
            verdict = new StructuredVerdict(
                    Verdict.FAIL, maximum,
                    maximum == Severity.CRITICAL ? 0.98d : 0.90d,
                    evidence, RequiredAction.FIX,
                    truncate("Static analysis found " + maximum + " reviewer evidence",
                            MAX_SUMMARY_CHARS),
                    true);
        } else {
            double confidence = maximum == Severity.MEDIUM ? 0.60d : 0.40d;
            verdict = new StructuredVerdict(
                    Verdict.INCONCLUSIVE, maximum, confidence,
                    evidence, RequiredAction.HUMAN_REVIEW,
                    truncate("Static analysis evidence requires reviewer confirmation",
                            MAX_SUMMARY_CHARS),
                    true);
        }
        return VerdictExtractionResult.from(NAME, verdict);
    }

    private boolean invalidEvidence(StaticAnalysisEvidence evidence) {
        if (evidence == null || evidence.sourceType() == null || evidence.severity() == null) {
            return true;
        }
        return evidence.sourceType() != EvidenceSourceType.REVIEWER_CODE_INTELLIGENCE
                && evidence.sourceType() != EvidenceSourceType.REVIEWER_STATIC_PATTERN;
    }

    private String withPatternId(StaticAnalysisEvidence evidence) {
        if (evidence.patternId() == null || evidence.patternId().isBlank()) {
            return evidence.message();
        }
        return "[" + evidence.patternId() + "] " + evidence.message();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...[truncated]";
    }
}
