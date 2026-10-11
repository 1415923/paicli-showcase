package com.paicli.provider.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.provider.ProviderResponseEnvelope;

/** Allowlist-only Provider-specific normalization. */
public final class ProviderObservationNormalizer {
    private ProviderObservationNormalizer() { }

    public static ProviderCallObservation ollama(JsonNode root,
            ProviderResponseEnvelope.ToolCallCardinality cardinality,
            Integer toolCallCount, String safeRequestId) {
        Integer input = nonNegative(root, "prompt_eval_count");
        Integer output = nonNegative(root, "eval_count");
        Integer total = input != null && output != null ? safeSum(input, output) : null;
        return observation(input, output, total, null, null, null,
                toolCallCount, cardinality, text(root, "done_reason"), safeRequestId,
                safeRequestId == null
                        ? ProviderCallObservation.ResponseIdentityProvenance
                                .PROVIDER_NOT_RETURNED
                        : ProviderCallObservation.ResponseIdentityProvenance
                                .PROVIDER_RETURNED);
    }

    public static ProviderCallObservation openAiCompatible(JsonNode root,
            ProviderResponseEnvelope.ToolCallCardinality cardinality,
            Integer toolCallCount, String finishReason, String safeRequestId) {
        return openAiCompatible(root, cardinality, toolCallCount, finishReason,
                safeRequestId, safeRequestId == null
                        ? ProviderCallObservation.ResponseIdentityProvenance
                                .PROVIDER_NOT_RETURNED
                        : ProviderCallObservation.ResponseIdentityProvenance
                                .PROVIDER_RETURNED);
    }

    public static ProviderCallObservation openAiCompatible(JsonNode root,
            ProviderResponseEnvelope.ToolCallCardinality cardinality,
            Integer toolCallCount, String finishReason, String safeRequestId,
            ProviderCallObservation.ResponseIdentityProvenance identityProvenance) {
        JsonNode usage = root == null ? null : root.get("usage");
        Integer input = nonNegative(usage, "prompt_tokens");
        Integer output = nonNegative(usage, "completion_tokens");
        Integer total = nonNegative(usage, "total_tokens");
        Integer cacheRead = nonNegative(usage, "prompt_cache_hit_tokens");
        if (cacheRead == null) {
            cacheRead = nestedNonNegative(usage, "prompt_tokens_details", "cached_tokens");
        }
        Integer reasoning = nestedNonNegative(
                usage, "completion_tokens_details", "reasoning_tokens");
        return observation(input, output, total, cacheRead, null, reasoning,
                toolCallCount, cardinality, finishReason, safeRequestId,
                identityProvenance);
    }

    private static ProviderCallObservation observation(Integer input, Integer output,
            Integer total, Integer cacheRead, Integer cacheWrite, Integer reasoning,
            Integer toolCallCount,
            ProviderResponseEnvelope.ToolCallCardinality cardinality,
            String finishReason, String safeRequestId,
            ProviderCallObservation.ResponseIdentityProvenance identityProvenance) {
        int corePresent = count(input, output, total);
        ProviderCallObservation.UsageSource source = corePresent == 0
                ? ProviderCallObservation.UsageSource.NOT_EXPOSED
                : ProviderCallObservation.UsageSource.PROVIDER_REPORTED;
        ProviderCallObservation.UsageCoverage coverage = corePresent == 0
                ? ProviderCallObservation.UsageCoverage.NONE
                : input != null && output != null && total != null
                ? ProviderCallObservation.UsageCoverage.COMPLETE
                : ProviderCallObservation.UsageCoverage.PARTIAL;
        if(corePresent==0){cacheRead=null;cacheWrite=null;reasoning=null;}
        return new ProviderCallObservation(input, output, total, cacheRead, cacheWrite,
                reasoning, source, coverage, toolCallCount, cardinality,
                finishReason, safeRequestId, identityProvenance, null, false);
    }

    private static Integer nonNegative(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) return null;
        int number = value.intValue();
        return number >= 0 ? number : null;
    }

    private static Integer nestedNonNegative(JsonNode node, String parent, String field) {
        return node == null ? null : nonNegative(node.get(parent), field);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static Integer safeSum(int left, int right) {
        long sum = (long) left + right;
        return sum <= Integer.MAX_VALUE ? (int) sum : null;
    }

    private static int count(Object... values) {
        int count = 0;
        for (Object value : values) if (value != null) count++;
        return count;
    }
}
