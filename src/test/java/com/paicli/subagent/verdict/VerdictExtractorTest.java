package com.paicli.subagent.verdict;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerdictExtractorTest {

    private final VerdictExtractor extractor = new VerdictExtractor();

    @Test
    void jsonStrategySucceeds() {
        VerdictExtractionResult result = extractor.extract(VerdictExtractionRequest.modelOutput(
                JsonModePromptStrategyTest.validJson("FAIL", "FIX", "HIGH", "0.8")));
        assertEquals(JsonModePromptStrategy.NAME, result.strategyName());
        assertEquals(VerdictExtractionStatus.PARSED, result.status());
    }

    @Test
    void markerStrategySucceedsAsFallback() {
        VerdictExtractionResult result = extractor.extract(VerdictExtractionRequest.modelOutput("""
                [VERDICT: FAIL]
                [SEVERITY: HIGH]
                [CONFIDENCE: 0.8]
                [EVIDENCE: REVIEWER_MODEL:A.java:2:review issue]
                [REQUIRED_ACTION: FIX]
                [SUMMARY: marker fallback]
                """));
        assertEquals(MarkerProtocolStrategy.NAME, result.strategyName());
        assertEquals(VerdictExtractionStatus.FALLBACK_PARSED, result.status());
        assertEquals(Verdict.FAIL, result.verdict().verdict());
        assertTrue(result.errorExcerpt().contains(JsonModePromptStrategy.NAME));
    }

    @Test
    void providerStructuredAdapterParsesFixtureWithoutCallingProvider() {
        VerdictExtractionResult result = extractor.extract(new VerdictExtractionRequest(
                JsonModePromptStrategyTest.validJson("PASS", "NONE", "NONE", "0.9"),
                VerdictExtractionMode.PROVIDER_STRUCTURED, null));
        assertEquals(ProviderStructuredOutputStrategy.NAME, result.strategyName());
        assertEquals(Verdict.PASS, result.verdict().verdict());
    }

    @Test
    void allStrategiesFailToInconclusiveWithParseErrorEvidence() {
        VerdictExtractionResult result = extractor.extract(
                VerdictExtractionRequest.modelOutput("not a verdict"));
        assertEquals(VerdictExtractionStatus.INCONCLUSIVE, result.status());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertEquals(EvidenceSourceType.PARSE_ERROR,
                result.verdict().evidence().getFirst().sourceType());
        assertNotNull(result.errorExcerpt());
    }

    @Test
    void malformedVerdictNeverBecomesPass() {
        VerdictExtractionResult result = extractor.extract(VerdictExtractionRequest.modelOutput(
                JsonModePromptStrategyTest.validJson("PASS", "FIX", "NONE", "1.4")));
        assertNotEquals(Verdict.PASS, result.verdict().verdict());
        assertFalse(result.verdict().parsed());
    }
}
