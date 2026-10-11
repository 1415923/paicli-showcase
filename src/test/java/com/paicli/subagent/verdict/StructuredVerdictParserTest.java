package com.paicli.subagent.verdict;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StructuredVerdictParserTest {

    private final StructuredVerdictParser parser = new StructuredVerdictParser();

    @Test
    void validJsonVerdictParsesStructuredVerdict() {
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "FAIL",
                  "severity": "HIGH",
                  "confidence": 0.85,
                  "evidence": [
                    {
                      "source_type": "REVIEWER_CODE_INTELLIGENCE",
                      "file": "src/main/java/App.java",
                      "line": 12,
                      "message": "Direct caller lacks null guard"
                    }
                  ],
                  "required_action": "FIX",
                  "summary": "Fix required"
                }
                """);

        assertTrue(verdict.parsed());
        assertEquals(Verdict.FAIL, verdict.verdict());
        assertEquals(Severity.HIGH, verdict.severity());
        assertEquals(RequiredAction.FIX, verdict.requiredAction());
        assertEquals(EvidenceSourceType.REVIEWER_CODE_INTELLIGENCE, verdict.evidence().getFirst().sourceType());
    }

    @Test
    void malformedJsonBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseJson("{not-json");

        assertFalse(verdict.parsed());
        assertEquals(Verdict.INCONCLUSIVE, verdict.verdict());
        assertEquals(RequiredAction.HUMAN_REVIEW, verdict.requiredAction());
        assertEquals(EvidenceSourceType.PARSE_ERROR, verdict.evidence().getFirst().sourceType());
    }

    @Test
    void markerProtocolParsesVerdict() {
        StructuredVerdict verdict = parser.parseMarker("""
                [VERDICT: FAIL]
                [SEVERITY: MEDIUM]
                [CONFIDENCE: 0.7]
                [EVIDENCE: REVIEWER_STATIC_PATTERN:src/main/java/App.java:22:empty catch block]
                [REQUIRED_ACTION: FIX]
                [SUMMARY: Static review found a failure pattern]
                """);

        assertTrue(verdict.parsed());
        assertEquals(Verdict.FAIL, verdict.verdict());
        assertEquals(Severity.MEDIUM, verdict.severity());
        assertEquals(22, verdict.evidence().getFirst().line());
    }

    @Test
    void malformedMarkerBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseMarker("""
                [VERDICT: FAIL]
                [SEVERITY: HIGH]
                [CONFIDENCE: 0.9]
                [EVIDENCE: src/main/java/App.java:22:missing source type]
                [REQUIRED_ACTION: FIX]
                [SUMMARY: invalid marker]
                """);

        assertFalse(verdict.parsed());
        assertEquals(Verdict.INCONCLUSIVE, verdict.verdict());
    }

    @Test
    void missingRequiredFieldsBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "PASS",
                  "severity": "NONE",
                  "confidence": 1.0,
                  "evidence": [
                    {"source_type": "REVIEWER_MODEL", "message": "ok"}
                  ]
                }
                """);

        assertFalse(verdict.parsed());
        assertTrue(verdict.summary().contains("missing required"));
    }

    @Test
    void evidenceWithoutSourceTypeBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "FAIL",
                  "severity": "LOW",
                  "confidence": 0.5,
                  "evidence": [{"message": "missing source type"}],
                  "required_action": "FIX",
                  "summary": "bad evidence"
                }
                """);

        assertFalse(verdict.parsed());
        assertTrue(verdict.summary().contains("source_type"));
    }

    @Test
    void passWithFixActionBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "PASS",
                  "severity": "NONE",
                  "confidence": 0.9,
                  "evidence": [{"source_type": "REVIEWER_MODEL", "message": "looks ok"}],
                  "required_action": "FIX",
                  "summary": "contradiction"
                }
                """);

        assertFalse(verdict.parsed());
        assertEquals(Verdict.INCONCLUSIVE, verdict.verdict());
        assertEquals(RequiredAction.HUMAN_REVIEW, verdict.requiredAction());
    }

    @Test
    void confidenceOutOfRangeBecomesInconclusive() {
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "FAIL",
                  "severity": "HIGH",
                  "confidence": 1.5,
                  "evidence": [{"source_type": "REVIEWER_MODEL", "message": "too confident"}],
                  "required_action": "FIX",
                  "summary": "bad confidence"
                }
                """);

        assertFalse(verdict.parsed());
        assertTrue(verdict.summary().contains("confidence"));
    }

    @Test
    void longSummaryAndEvidenceAreTruncated() {
        String longText = "x".repeat(2_000);
        StructuredVerdict verdict = parser.parseJson("""
                {
                  "verdict": "FAIL",
                  "severity": "LOW",
                  "confidence": 0.4,
                  "evidence": [{"source_type": "REVIEWER_MODEL", "message": "%s"}],
                  "required_action": "HUMAN_REVIEW",
                  "summary": "%s"
                }
                """.formatted(longText, longText));

        assertTrue(verdict.parsed());
        assertTrue(verdict.summary().length() < 1_100);
        assertTrue(verdict.evidence().getFirst().message().length() < 1_100);
    }

    @Test
    void parseErrorDoesNotLeakLongRawText() {
        String raw = "not-json-" + "secret".repeat(500);
        StructuredVerdict verdict = parser.parseJson(raw);

        assertFalse(verdict.parsed());
        assertTrue(verdict.summary().length() < 300);
        assertFalse(verdict.summary().contains("secretsecretsecretsecret"));
    }
}
