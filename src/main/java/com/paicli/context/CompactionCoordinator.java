package com.paicli.context;

import com.paicli.context.ContextModels.Budget;
import com.paicli.context.ContextModels.MaintenanceAuthority;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.ContextGenerationStore;
import com.paicli.state.ContextGenerationStore.Generation;
import com.paicli.state.ContextGenerationStore.Pointer;
import com.paicli.state.ContextGenerationStore.Published;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StateStore;
import java.sql.SQLException;
import java.util.Objects;

/** Shared, bounded manual/automatic compaction transaction coordinator. */
public final class CompactionCoordinator {
    public static final int SOFT_TRIGGER_PERCENT=80;
    private final StateStore state;

    public CompactionCoordinator(StateStore state){this.state=Objects.requireNonNull(state);}

    public boolean shouldAutoCompact(Budget budget){
        if(budget==null||budget.contextLimit()==null)return false;
        long threshold=Math.max(1L,(long)budget.contextLimit()*SOFT_TRIGGER_PERCENT/100L);
        return budget.decision()==Budget.Decision.COMPACTION_REQUIRED||budget.total()>=threshold;
    }

    /** One Provider admission may call this method at most once. */
    public Outcome autoCompact(Pointer source,FencedExecutionGuard guard)throws SQLException{
        Objects.requireNonNull(source);Objects.requireNonNull(guard);
        ExecutionAuthoritySnapshot authority=guard.current().snapshot();
        Generation candidate;
        try{
            candidate=state.contexts.createOrReuseAutoCandidate(source,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,authority);
        }catch(ContextGenerationStore.ContextFailure failure){
            if("COMPACTION_NOT_APPLICABLE".equals(failure.getMessage()))
                throw new ContextGenerationStore.ContextFailure(
                        "COMPACTION_NO_EFFECTIVE_REDUCTION");
            throw failure;
        }
        if(candidate.lifecycle()==ContextModels.Lifecycle.PREPARING)
            throw new ContextGenerationStore.ContextFailure("COMPACTION_IN_PROGRESS");
        if(state.contexts.budget(candidate.contextId()).inputEstimate()
                >=state.contexts.budget(source.contextGenerationId()).inputEstimate())
            throw new ContextGenerationStore.ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
        Published applied=state.contexts.applyAutoCandidate(candidate.contextId(),source,authority);
        return outcome(source,candidate,applied);
    }

    public Outcome manualCompact(MaintenanceAuthority authority)throws SQLException{
        Objects.requireNonNull(authority);
        int before=0;for(var target:authority.targets())before=Math.addExact(before,
                state.contexts.budget(target.sourceContextGenerationId()).inputEstimate());
        var applied=state.contexts.compactMaintenanceBundle(authority,
                DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        int after=0;long version=0;StringBuilder ids=new StringBuilder();
        for(Published value:applied){after=Math.addExact(after,value.budget().inputEstimate());
            version=Math.max(version,value.pointerVersion());if(!ids.isEmpty())ids.append(',');
            ids.append(value.contextGenerationId(),0,Math.min(16,value.contextGenerationId().length()));}
        if(after>=before)throw new ContextGenerationStore.ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
        return new Outcome("bundle",ids.toString(),before,after,version,null);
    }

    private Outcome outcome(Pointer source,Generation candidate,Published applied)throws SQLException{
        Budget before=state.contexts.budget(source.contextGenerationId());
        Budget after=applied.budget();
        if(after.inputEstimate()>=before.inputEstimate())
            throw new ContextGenerationStore.ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
        return new Outcome(source.contextGenerationId(),applied.contextGenerationId(),
                before.inputEstimate(),after.inputEstimate(),applied.pointerVersion(),candidate.operation());
    }

    public record Outcome(String sourceGenerationId,String generationId,int beforeEstimatedTokens,
            int afterEstimatedTokens,long pointerVersion,ContextModels.OperationKind operation){
        public String safeSummary(){return "Context compacted · before="+beforeEstimatedTokens
                +" after="+afterEstimatedTokens+" generation="+shortId(generationId);}
        private static String shortId(String value){return value.substring(0,Math.min(8,value.length()));}
    }
}
