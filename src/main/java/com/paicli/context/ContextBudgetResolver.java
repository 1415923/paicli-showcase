package com.paicli.context;

import com.paicli.context.ContextModels.*;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolDefinition;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Versioned deterministic conservative UTF-8 estimator and fail-closed budget gate. */
public final class ContextBudgetResolver {
    public static final String ESTIMATOR_VERSION="UTF8_CONSERVATIVE_V2";
    public static final int PROTOCOL_OVERHEAD_TOKENS=32;
    public int estimate(String value){
        if(value==null||value.isEmpty())return 1;
        long bytes=value.getBytes(StandardCharsets.UTF_8).length;
        return Math.toIntExact(Math.min(Integer.MAX_VALUE,(bytes+2L)/3L+4L));
    }
    public Budget resolve(List<AgentMessage> messages,List<ToolDefinition> tools,
            CapabilityProfile profile,boolean compactable) {
        int input=PROTOCOL_OVERHEAD_TOKENS;
        for(AgentMessage message:messages)input=Math.addExact(input,
                estimate(message.role()+"\n"+message.type()+"\n"+message.content()));
        int schemas=0;
        for(ToolDefinition tool:tools)schemas=Math.addExact(schemas,
                estimate(tool.name()+"\n"+tool.description()+"\n"+tool.parameterSchema()));
        Integer limit=profile.contextLimit();
        Budget.Decision decision;
        if(limit==null||profile.provenance()==LimitProvenance.UNKNOWN_LIMIT)
            decision=Budget.Decision.CONTEXT_BUDGET_UNKNOWN;
        else {
            long total=(long)input+schemas+profile.outputReserve()+profile.safetyMargin();
            decision=total<=limit?Budget.Decision.READY
                    :(compactable?Budget.Decision.COMPACTION_REQUIRED:Budget.Decision.CONTEXT_BUDGET_EXCEEDED);
        }
        return new Budget(input,schemas,profile.outputReserve(),profile.safetyMargin(),limit,
                profile.provenance(),EstimateProvenance.DERIVED_ESTIMATE,decision);
    }
}
