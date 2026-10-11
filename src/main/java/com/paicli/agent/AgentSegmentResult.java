package com.paicli.agent;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse.ProviderErrorKind;

import java.util.List;

/** Result of one runner-managed AgentLoop execution segment. */
public record AgentSegmentResult(
        String answer,
        int startIteration,
        int endIteration,
        int iterationsExecuted,
        List<AgentMessage> conversation,
        List<String> messages,
        Outcome outcome,
        ProviderErrorKind providerErrorKind,
        boolean userDeniedTool,
        String providerOccurrenceId
) {
    public enum Outcome { SUCCESS, MAX_ITERATIONS, PROVIDER_ERROR, PAUSED }

    public AgentSegmentResult(String answer, int startIteration, int endIteration,
            int iterationsExecuted, List<AgentMessage> conversation,
            List<String> messages, boolean completed) {
        this(answer, startIteration, endIteration, iterationsExecuted, conversation, messages,
                completed ? Outcome.SUCCESS : Outcome.MAX_ITERATIONS, null, false,null);
    }

    public AgentSegmentResult(String answer,int startIteration,int endIteration,
            int iterationsExecuted,List<AgentMessage> conversation,List<String> messages,
            Outcome outcome,ProviderErrorKind providerErrorKind,boolean userDeniedTool){
        this(answer,startIteration,endIteration,iterationsExecuted,conversation,messages,outcome,
                providerErrorKind,userDeniedTool,null);
    }

    public AgentSegmentResult {
        conversation = List.copyOf(conversation);
        messages = List.copyOf(messages);
        if ((outcome == Outcome.PROVIDER_ERROR) != (providerErrorKind != null)) {
            throw new IllegalArgumentException("Provider error outcome requires exactly one failure kind");
        }
    }

    public boolean completed() {
        return outcome == Outcome.SUCCESS;
    }
}
