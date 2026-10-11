package com.paicli.agent;

import com.paicli.model.ModelResponse.ProviderErrorKind;

import java.util.List;

/**
 * Final result from an agent loop execution.
 *
 * @param answer       the final text answer (null if loop hit max iterations without final)
 * @param iterations   number of loop iterations executed
 * @param messages     full conversation history
 * @param outcome      typed loop outcome
 * @param providerErrorKind bounded Provider failure classification when applicable
 */
public record AgentResult(
        String answer,
        int iterations,
        List<String> messages,
        Outcome outcome,
        ProviderErrorKind providerErrorKind
) {
    public enum Outcome { SUCCESS, MAX_ITERATIONS, PROVIDER_ERROR }

    public AgentResult(String answer, int iterations, List<String> messages,
                       boolean completed) {
        this(answer, iterations, messages,
                completed ? Outcome.SUCCESS : Outcome.MAX_ITERATIONS, null);
    }

    public AgentResult {
        messages = List.copyOf(messages);
        if ((outcome == Outcome.PROVIDER_ERROR) != (providerErrorKind != null)) {
            throw new IllegalArgumentException("Provider error outcome requires exactly one failure kind");
        }
    }

    public static AgentResult completed(String answer, int iterations, List<String> messages) {
        return new AgentResult(answer, iterations, messages, Outcome.SUCCESS, null);
    }

    public static AgentResult maxIterationsReached(int iterations, List<String> messages) {
        return new AgentResult(null, iterations, messages, Outcome.MAX_ITERATIONS, null);
    }

    public static AgentResult providerError(int iterations, List<String> messages,
                                            ProviderErrorKind kind) {
        return new AgentResult(null, iterations, messages, Outcome.PROVIDER_ERROR, kind);
    }

    public boolean completed() {
        return outcome == Outcome.SUCCESS;
    }
}
