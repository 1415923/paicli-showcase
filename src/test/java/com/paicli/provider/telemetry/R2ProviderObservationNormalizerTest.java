package com.paicli.provider.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.provider.ProviderResponseEnvelope.ToolCallCardinality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class R2ProviderObservationNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void ollamaUsageAndFinishReasonAreAllowlisted() throws Exception {
        var observation = ProviderObservationNormalizer.ollama(json.readTree("""
                {"prompt_eval_count":12,"eval_count":7,"done_reason":"stop",
                 "secret":"must-not-escape"}
                """), ToolCallCardinality.ZERO, 0, null);
        assertEquals(12, observation.inputTokens());
        assertEquals(7, observation.outputTokens());
        assertEquals(19, observation.totalTokens());
        assertEquals("stop", observation.finishReason());
        assertFalse(observation.toString().contains("must-not-escape"));
    }

    @Test void openAiCompatibleDistinguishesCompletePartialMissingAndRealZero()
            throws Exception {
        var complete = ProviderObservationNormalizer.openAiCompatible(json.readTree("""
                {"usage":{"prompt_tokens":0,"completion_tokens":4,"total_tokens":4,
                 "prompt_tokens_details":{"cached_tokens":0},
                 "completion_tokens_details":{"reasoning_tokens":2}}}
                """), ToolCallCardinality.ONE, 1, "tool_calls", "req-safe");
        assertEquals(ProviderCallObservation.UsageCoverage.COMPLETE,
                complete.usageCoverage());
        assertEquals(0, complete.inputTokens());
        assertEquals(0, complete.cacheReadTokens());
        assertEquals(ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_RETURNED,
                complete.responseIdentityProvenance());
        var partial = ProviderObservationNormalizer.openAiCompatible(json.readTree(
                "{\"usage\":{\"total_tokens\":9}}"),
                ToolCallCardinality.ZERO, 0, null, null);
        assertEquals(ProviderCallObservation.UsageCoverage.PARTIAL,
                partial.usageCoverage());
        var missing = ProviderObservationNormalizer.openAiCompatible(json.readTree("{}"),
                ToolCallCardinality.ZERO, 0, null, null);
        assertNull(missing.totalTokens());
        assertEquals(ProviderCallObservation.UsageSource.NOT_EXPOSED,
                missing.usageSource());
        assertEquals(ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_NOT_RETURNED,
                missing.responseIdentityProvenance());
    }

    @Test void invalidOrUnboundedProviderFieldsBecomeNotExposed() throws Exception {
        var observation = ProviderObservationNormalizer.openAiCompatible(json.readTree("""
                {"usage":{"prompt_tokens":-1,"completion_tokens":"4"}}
                """), ToolCallCardinality.INVALID, 2,
                "unsafe finish reason with spaces", "Authorization: secret");
        assertNull(observation.inputTokens());
        assertNull(observation.outputTokens());
        assertNull(observation.finishReason());
        assertNull(observation.safeRequestId());
        var auxiliaryOnly=ProviderObservationNormalizer.openAiCompatible(json.readTree(
                "{\"usage\":{\"prompt_tokens_details\":{\"cached_tokens\":3}}}"),
                ToolCallCardinality.ZERO,0,null,null);
        assertEquals(ProviderCallObservation.UsageSource.NOT_EXPOSED,
                auxiliaryOnly.usageSource());
        assertNull(auxiliaryOnly.cacheReadTokens());
    }

    @Test void deepSeekTopLevelCacheHitUsageIsProviderReported() throws Exception {
        var observation = ProviderObservationNormalizer.openAiCompatible(json.readTree("""
                {"usage":{"prompt_tokens":11,"completion_tokens":5,"total_tokens":16,
                 "prompt_cache_hit_tokens":7,"prompt_cache_miss_tokens":4}}
                """), ToolCallCardinality.ZERO, 0, "stop", "req-safe");
        assertEquals(ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                observation.usageSource());
        assertEquals(11, observation.inputTokens());
        assertEquals(7, observation.cacheReadTokens());
    }
}
