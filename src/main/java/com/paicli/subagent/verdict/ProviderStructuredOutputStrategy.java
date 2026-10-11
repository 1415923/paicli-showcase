package com.paicli.subagent.verdict;

/** Adapter point for provider-enforced structured output; R2 performs no provider call. */
public final class ProviderStructuredOutputStrategy implements VerdictExtractionStrategy {

    public static final String NAME = "provider-structured-output";

    private final StructuredVerdictParser parser;

    public ProviderStructuredOutputStrategy() {
        this(new StructuredVerdictParser());
    }

    ProviderStructuredOutputStrategy(StructuredVerdictParser parser) {
        this.parser = parser;
    }

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(VerdictExtractionRequest request) {
        return request.mode() == VerdictExtractionMode.PROVIDER_STRUCTURED;
    }

    @Override
    public VerdictExtractionResult extract(VerdictExtractionRequest request) {
        return VerdictExtractionResult.from(NAME, parser.parseJson(request.rawOutput()));
    }
}
