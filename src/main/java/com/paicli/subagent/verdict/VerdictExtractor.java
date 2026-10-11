package com.paicli.subagent.verdict;

import java.util.ArrayList;
import java.util.List;

public final class VerdictExtractor {

    private static final String FALLBACK_NAME = "fallback-chain";

    private final List<VerdictExtractionStrategy> strategies;

    public VerdictExtractor() {
        this(List.of(
                new ProviderStructuredOutputStrategy(),
                new JsonModePromptStrategy(),
                new MarkerProtocolStrategy(),
                new StaticAnalysisEvidenceStrategy()));
    }

    public VerdictExtractor(List<VerdictExtractionStrategy> strategies) {
        if (strategies == null || strategies.isEmpty()) {
            throw new IllegalArgumentException("at least one extraction strategy is required");
        }
        this.strategies = List.copyOf(strategies);
    }

    public VerdictExtractionResult extract(VerdictExtractionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        List<String> failures = new ArrayList<>();
        int attempts = 0;
        for (VerdictExtractionStrategy strategy : strategies) {
            if (!strategy.supports(request)) {
                continue;
            }
            attempts++;
            VerdictExtractionResult result = strategy.extract(request);
            if (result.verdict().parsed()) {
                return attempts == 1
                        ? result
                        : result.asFallback(String.join("; ", failures));
            }
            failures.add(strategy.strategyName() + ": " + result.errorExcerpt());
        }

        String reason = failures.isEmpty()
                ? "parse_error: no extraction strategy supports the request"
                : "parse_error: all extraction strategies failed";
        return new VerdictExtractionResult(
                StructuredVerdict.inconclusive(reason),
                FALLBACK_NAME,
                VerdictExtractionStatus.INCONCLUSIVE,
                String.join("; ", failures));
    }
}
