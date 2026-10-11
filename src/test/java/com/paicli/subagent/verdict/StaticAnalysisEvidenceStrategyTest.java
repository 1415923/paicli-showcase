package com.paicli.subagent.verdict;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StaticAnalysisEvidenceStrategyTest {

    private final StaticAnalysisEvidenceStrategy strategy = new StaticAnalysisEvidenceStrategy();

    @Test
    void noEvidenceIsInconclusiveBecauseAbsenceIsNotProofOfPass() {
        VerdictExtractionResult result = extract(List.of());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertFalse(result.verdict().parsed());
    }

    @Test
    void lowEvidenceRequiresHumanReview() {
        VerdictExtractionResult result = extract(List.of(evidence(Severity.LOW)));
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertEquals(RequiredAction.HUMAN_REVIEW, result.verdict().requiredAction());
        assertTrue(result.verdict().parsed());
    }

    @Test
    void highAndCriticalEvidenceFailClosed() {
        for (Severity severity : List.of(Severity.HIGH, Severity.CRITICAL)) {
            VerdictExtractionResult result = extract(List.of(evidence(severity)));
            assertEquals(Verdict.FAIL, result.verdict().verdict());
            assertEquals(severity, result.verdict().severity());
            assertEquals(RequiredAction.FIX, result.verdict().requiredAction());
        }
    }

    @Test
    void retainsReviewerEvidenceSourceAndPatternId() {
        VerdictExtractionResult result = extract(List.of(evidence(Severity.HIGH)));
        VerdictEvidence item = result.verdict().evidence().getFirst();
        assertEquals(EvidenceSourceType.REVIEWER_STATIC_PATTERN, item.sourceType());
        assertTrue(item.message().contains("EMPTY_CATCH"));
    }

    @Test
    void rejectsVerifierEvidenceInsteadOfMixingJUnitOrCommandOutput() {
        StaticAnalysisEvidence verifierEvidence = new StaticAnalysisEvidence(
                EvidenceSourceType.VERIFIER_JUNIT_XML, "TEST.xml", 1,
                "test failure", Severity.HIGH, "JUNIT");
        VerdictExtractionResult result = extract(List.of(verifierEvidence));
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertEquals(EvidenceSourceType.PARSE_ERROR,
                result.verdict().evidence().getFirst().sourceType());
    }

    private VerdictExtractionResult extract(List<StaticAnalysisEvidence> evidence) {
        return strategy.extract(VerdictExtractionRequest.staticEvidence(evidence));
    }

    private StaticAnalysisEvidence evidence(Severity severity) {
        return new StaticAnalysisEvidence(
                EvidenceSourceType.REVIEWER_STATIC_PATTERN,
                "src/main/java/App.java", 18, "empty catch block", severity, "EMPTY_CATCH");
    }
}
