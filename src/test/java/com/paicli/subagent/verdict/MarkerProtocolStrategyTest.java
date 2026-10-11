package com.paicli.subagent.verdict;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarkerProtocolStrategyTest {

    private final MarkerProtocolStrategy strategy = new MarkerProtocolStrategy();

    @Test
    void extractsValidMarkerVerdict() {
        VerdictExtractionResult result = extract(validMarker());

        assertTrue(result.verdict().parsed());
        assertEquals(Verdict.FAIL, result.verdict().verdict());
        assertEquals(MarkerProtocolStrategy.NAME, result.strategyName());
    }

    @Test
    void extractsMultipleEvidenceMarkers() {
        VerdictExtractionResult result = extract(validMarker().replace(
                "[REQUIRED_ACTION: FIX]",
                "[EVIDENCE: REVIEWER_CODE_INTELLIGENCE:B.java:7:second issue]\n"
                        + "[REQUIRED_ACTION: FIX]"));

        assertEquals(2, result.verdict().evidence().size());
        assertEquals(EvidenceSourceType.REVIEWER_CODE_INTELLIGENCE,
                result.verdict().evidence().get(1).sourceType());
    }

    @Test
    void malformedMarkerFailsClosed() {
        assertInconclusive(extract("[VERDICT FAIL]\n[SUMMARY: invalid]"));
    }

    @Test
    void evidenceWithoutSourceTypeFailsClosed() {
        assertInconclusive(extract(validMarker().replace(
                "REVIEWER_STATIC_PATTERN:A.java:4:unsafe branch",
                "A.java:4:unsafe branch")));
    }

    @Test
    void longSummaryAndEvidenceAreTruncated() {
        String longText = "x".repeat(2_000);
        VerdictExtractionResult result = extract(validMarker()
                .replace("unsafe branch", longText)
                .replace("review failed", longText));

        assertTrue(result.verdict().parsed());
        assertTrue(result.verdict().summary().length() < 1_100);
        assertTrue(result.verdict().evidence().getFirst().message().length() < 1_100);
    }

    @Test
    void passWithFixFailsClosed() {
        assertInconclusive(extract(validMarker()
                .replace("[VERDICT: FAIL]", "[VERDICT: PASS]")
                .replace("[SEVERITY: HIGH]", "[SEVERITY: NONE]")));
    }

    private VerdictExtractionResult extract(String output) {
        return strategy.extract(new VerdictExtractionRequest(
                output, VerdictExtractionMode.MARKER, null));
    }

    private void assertInconclusive(VerdictExtractionResult result) {
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertFalse(result.verdict().parsed());
        assertEquals(EvidenceSourceType.PARSE_ERROR,
                result.verdict().evidence().getFirst().sourceType());
    }

    private String validMarker() {
        return """
                [VERDICT: FAIL]
                [SEVERITY: HIGH]
                [CONFIDENCE: 0.8]
                [EVIDENCE: REVIEWER_STATIC_PATTERN:A.java:4:unsafe branch]
                [REQUIRED_ACTION: FIX]
                [SUMMARY: review failed]
                """;
    }
}
