package com.paicli.subagent.verdict;

public interface VerdictExtractionStrategy {

    String strategyName();

    boolean supports(VerdictExtractionRequest request);

    VerdictExtractionResult extract(VerdictExtractionRequest request);
}
