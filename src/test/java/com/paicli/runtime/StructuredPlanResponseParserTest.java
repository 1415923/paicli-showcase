package com.paicli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuredPlanResponseParserTest {
    private static final String VALID = """
            {"acceptanceCriteria":["files are listed"],"assumptions":[],
             "risks":[],"verificationStrategy":"inspect the tool result",
             "steps":[{"title":"Inspect","definition":"List workspace files",
             "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
             "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
             "toolRequirements":[{"requirementId":"inspect-readme",
             "requirementOrder":0,"toolName":"read_file","effectClass":"READ",
             "authoritySelector":{"kind":"EXACT_PATH","path":"README.md"},
             "minSuccesses":1,"maxOccurrences":1,
             "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private final StructuredPlanResponseParser parser =
            new StructuredPlanResponseParser();

    @Test
    void acceptsStrictJsonAndSingleMarkdownFence() {
        assertEquals(1, parser.parse(VALID, 64_000).path("steps").size());
        JsonNode fenced = parser.parse("```json\n" + VALID + "\n```", 64_000);
        assertEquals("Inspect", fenced.path("steps").get(0).path("title").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Here is the requested plan:\n%s",
            "%s\nThis plan is ready for review."
    })
    void acceptsOneBalancedObjectWithOnlyShortPreambleOrPostscript(String envelope) {
        assertEquals(1, parser.parse(envelope.formatted(VALID), 64_000)
                .path("steps").size());
    }

    @Test
    void rejectsMultipleObjectsInsteadOfSelectingAnArbitraryOne() {
        var error = assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse(VALID + "\n" + VALID, 128_000));
        assertTrue(error.getMessage().contains("MULTIPLE_JSON_OBJECTS"));
    }

    @Test
    void classifiesSyntaxAndTruncationWithoutLeakingResponseBody() {
        String secret = "secret-response-sentinel";
        var syntax = assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse("{\"steps\":, \"" + secret + "\":true}", 64_000));
        assertTrue(syntax.getMessage().contains("JSON_SYNTAX_ERROR"));
        assertFalse(syntax.getMessage().contains(secret));

        var truncated = assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse("{\"steps\":[{\"title\":\"Inspect\"}", 64_000));
        assertTrue(truncated.getMessage().contains("TRUNCATED_JSON_OBJECT"));
        assertTrue(truncated.getMessage().contains("truncated=true"));
    }

    @Test
    void rejectsEmptyContentMissingRequiredFieldsAndEnumMismatch() {
        assertTrue(assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse("   ", 64_000)).getMessage()
                .contains("EMPTY_CONTENT"));

        var missing = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse("""
                        {"acceptanceCriteria":[],"assumptions":[],"risks":[],"steps":[]}
                        """, 64_000));
        assertTrue(missing.errors().stream()
                .anyMatch(value -> value.contains("verificationStrategy")));
        assertTrue(missing.errors().stream()
                .anyMatch(value -> value.contains("$.steps")));

        String mismatch = VALID.replace("ALL_REQUIRED_EVIDENCE", "MAYBE")
                .replace("\"READ\"", "\"MAGIC\"");
        var enums = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse(mismatch, 64_000));
        assertTrue(enums.errors().stream()
                .anyMatch(value -> value.contains("completionRule:ENUM_MISMATCH")));
        assertTrue(enums.errors().stream()
                .anyMatch(value -> value.contains("effectClass:ENUM_MISMATCH")));
    }

    @Test
    void rejectsMissingScopeProviderDerivedFieldsAndUnsupportedTool() {
        String missing = VALID.replace(
                "\"requiredEvidenceKinds\":[\"TOOL_CALL\"]",
                "\"requiredEvidenceKinds\":[\"SNAPSHOT\"]");
        var noScope = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse(missing, 64_000));
        assertTrue(noScope.errors().stream().anyMatch(value ->
                value.contains("toolRequirements:TOOL_CALL_SCOPE_MISMATCH")));

        String providerDerived = VALID.replace(
                "\"path\":\"README.md\"",
                "\"path\":\"README.md\",\"digest\":\"" + "a".repeat(64) + "\"");
        var derived = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse(providerDerived, 64_000));
        assertTrue(derived.errors().stream().anyMatch(value ->
                value.contains("authoritySelector:UNSUPPORTED_OR_INVALID")));

        String unsupported = VALID.replace("\"toolName\":\"read_file\"",
                        "\"toolName\":\"apply_patch\"")
                .replace("\"kind\":\"EXACT_PATH\",\"path\":\"README.md\"",
                        "\"kind\":\"EXACT_PATH_SET\",\"paths\":[\"README.md\"]");
        var unsupportedTool = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse(unsupported, 64_000));
        assertTrue(unsupportedTool.errors().stream().anyMatch(value ->
                value.contains("authoritySelector:UNSUPPORTED_OR_INVALID")));
    }

    @Test
    void rejectsMultipleOrUnclosedFences() {
        var multiple = assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse("```json\n" + VALID + "\n```\n```json\n"
                        + VALID + "\n```", 128_000));
        assertTrue(multiple.getMessage()
                .contains("MULTIPLE_OR_UNCLOSED_JSON_FENCE"));

        var unclosed = assertThrows(StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse("```json\n" + VALID, 64_000));
        assertTrue(unclosed.getMessage()
                .contains("MULTIPLE_OR_UNCLOSED_JSON_FENCE"));
    }

    @Test
    void reportsOnlySchemaPathsAndCountsForCompatibilityTelemetry() {
        String invalid = VALID.replace(
                "\"kind\":\"EXACT_PATH\",\"path\":\"README.md\"",
                "\"kind\":\"EXACT_PATH\",\"recursive\":true");
        var summary = parser.schemaSummary(invalid);
        assertEquals(List.of(
                "$.steps[0].toolRequirements[0].authoritySelector.path"),
                summary.missingRequiredFields());
        assertEquals(List.of(
                "$.steps[0].toolRequirements[0].authoritySelector.__UNKNOWN_FIELD_1"),
                summary.unknownFields());
        assertEquals(0, parser.normalizationCount(invalid));
        assertEquals(1, parser.normalizationCount("```json\n" + VALID + "\n```"));
        assertFalse(summary.toString().contains("README.md"));
    }

    @Test
    void advisoryWireAcceptsIntentStepsWithoutStrictAuthorityFields() {
        StructuredPlanResponseParser advisory = new StructuredPlanResponseParser(
                PlanRuntimeMode.DEFAULT_ADVISORY);
        String minimal = """
                {"acceptanceCriteria":["answer grounded"],"assumptions":[],"risks":[],
                 "verificationStrategy":"inspect output","steps":[
                 {"title":"Inspect","definition":"Read the relevant source","dependsOn":[]}]}
                """;
        JsonNode parsed = advisory.parse(minimal, 64_000);
        assertEquals("advisory-plan-wire-v1", advisory.wireSchemaVersion());
        assertEquals("Inspect", parsed.path("steps").get(0).path("title").asText());
        assertFalse(advisory.requiredSchemaFields().stream()
                .anyMatch(field -> field.contains("authoritySelector")));
    }

    @Test
    void advisoryWireIgnoresNonAuthoritativeMalformedSelectorHintButStrictRejectsIt() {
        String malformed = VALID.replace(
                "\"kind\":\"EXACT_PATH\",\"path\":\"README.md\"",
                "\"kind\":\"EXACT_PATH\",\"path\":\"../forbidden\",\"recursive\":true");
        StructuredPlanResponseParser advisory = new StructuredPlanResponseParser(
                PlanRuntimeMode.DEFAULT_ADVISORY);
        assertEquals(1, advisory.parse(malformed, 64_000).path("steps").size());
        var strictFailure = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> parser.parse(malformed, 64_000));
        assertTrue(strictFailure.errors().stream().anyMatch(
                value -> value.contains("authoritySelector:UNSUPPORTED_OR_INVALID")));
    }

    @Test
    void advisoryWireStillRejectsMissingRequiredIntentOrInvalidDependencies() {
        StructuredPlanResponseParser advisory = new StructuredPlanResponseParser(
                PlanRuntimeMode.DEFAULT_ADVISORY);
        var missing = assertThrows(StructuredPlanResponseParser.SchemaFailure.class,
                () -> advisory.parse("""
                        {"acceptanceCriteria":[],"assumptions":[],"risks":[],
                         "verificationStrategy":"inspect","steps":[
                         {"title":"","definition":"work","dependsOn":[2]}]}
                        """, 64_000));
        assertTrue(missing.errors().stream().anyMatch(value -> value.contains("title")));
        assertTrue(missing.errors().stream().anyMatch(value -> value.contains("INVALID_STEP_INDEX")));
    }
}
