package com.paicli.subagent.verdict;

public final class MarkerProtocolStrategy implements VerdictExtractionStrategy {

    public static final String NAME = "marker-protocol";

    private final StructuredVerdictParser parser;

    public MarkerProtocolStrategy() {
        this(new StructuredVerdictParser());
    }

    MarkerProtocolStrategy(StructuredVerdictParser parser) {
        this.parser = parser;
    }

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(VerdictExtractionRequest request) {
        return request.mode() == VerdictExtractionMode.AUTO
                || request.mode() == VerdictExtractionMode.MARKER
                || request.mode() == VerdictExtractionMode.PROVIDER_STRUCTURED;
    }

    @Override
    public VerdictExtractionResult extract(VerdictExtractionRequest request) {
        return VerdictExtractionResult.from(NAME, parser.parseMarker(request.rawOutput()));
    }
}
