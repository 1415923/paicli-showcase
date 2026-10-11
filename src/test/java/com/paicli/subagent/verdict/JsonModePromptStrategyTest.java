package com.paicli.subagent.verdict;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonModePromptStrategyTest {

    private final JsonModePromptStrategy strategy = new JsonModePromptStrategy();

    @Test
    void extractsValidJsonVerdict() {
        VerdictExtractionResult result = extract(validJson("FAIL", "FIX", "HIGH", "0.8"));

        assertEquals(VerdictExtractionStatus.PARSED, result.status());
        assertEquals(Verdict.FAIL, result.verdict().verdict());
        assertEquals(JsonModePromptStrategy.NAME, result.strategyName());
    }

    @Test
    void extractsJsonFromCodeFence() {
        VerdictExtractionResult result = extract("```json\n" + validJson(
                "PASS", "NONE", "NONE", "0.9") + "\n```");

        assertTrue(result.verdict().parsed());
        assertEquals(Verdict.PASS, result.verdict().verdict());
    }

    @Test
    void extractsJsonSurroundedByBoundedExplanation() {
        VerdictExtractionResult result = extract(
                "Review result follows.\n" + validJson("FAIL", "RECHECK", "MEDIUM", "0.6")
                        + "\nEnd of review.");

        assertTrue(result.verdict().parsed());
        assertEquals(RequiredAction.RECHECK, result.verdict().requiredAction());
    }

    @Test
    void malformedJsonFailsClosed() {
        assertInconclusive(extract("before {not-json} after"));
    }

    @Test
    void missingRequiredFieldFailsClosed() {
        assertInconclusive(extract(validJson("FAIL", "FIX", "HIGH", "0.8")
                .replace("\"summary\": \"review summary\"", "")));
    }

    @Test
    void evidenceWithoutSourceTypeFailsClosed() {
        assertInconclusive(extract(validJson("FAIL", "FIX", "HIGH", "0.8")
                .replace("\"source_type\": \"REVIEWER_MODEL\",", "")));
    }

    @Test
    void confidenceOutOfRangeFailsClosed() {
        assertInconclusive(extract(validJson("FAIL", "FIX", "HIGH", "1.2")));
    }

    @Test
    void passWithFixFailsClosed() {
        assertInconclusive(extract(validJson("PASS", "FIX", "NONE", "0.9")));
    }

    private VerdictExtractionResult extract(String output) {
        return strategy.extract(new VerdictExtractionRequest(
                output, VerdictExtractionMode.JSON, null));
    }

    private void assertInconclusive(VerdictExtractionResult result) {
        assertEquals(VerdictExtractionStatus.INCONCLUSIVE, result.status());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertEquals(RequiredAction.HUMAN_REVIEW, result.verdict().requiredAction());
        assertEquals(EvidenceSourceType.PARSE_ERROR,
                result.verdict().evidence().getFirst().sourceType());
    }

    static String validJson(String verdict, String action, String severity, String confidence) {
        return """
                {
                  "verdict": "%s",
                  "severity": "%s",
                  "confidence": %s,
                  "evidence": [{
                    "source_type": "REVIEWER_MODEL",
                    "file": "src/main/java/App.java",
                    "line": 12,
                    "message": "bounded evidence"
                  }],
                  "required_action": "%s",
                  "summary": "review summary"
                }
                """.formatted(verdict, severity, confidence, action);
    }
}
