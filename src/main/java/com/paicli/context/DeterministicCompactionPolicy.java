package com.paicli.context;

import com.paicli.context.ContextModels.Budget;
import com.paicli.context.ContextModels.Compactability;
import com.paicli.context.ContextModels.LedgerFactKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Versioned provider-free policy that exposes deterministic, independently safe groups. */
public final class DeterministicCompactionPolicy {
    public static final String VERSION="R3_POLICY_V4";
    private static final int MIN_RECENT_SUPPORTED=4;
    private static final int MIN_RECENT_TOOL_PAIRS=4;
    private static final Set<String> SUPPORTED=Set.of("PLAN_STEP","ATTEMPT","EVIDENCE",
            "TOOL_OCCURRENCE","TOOL_RESULT","ARTIFACT_REFERENCE","VERIFICATION_RESULT",
            "REPAIR_ITERATION","DURABLE_ASSISTANT_RESULT","EVENT_FINAL",
            "COMPLETED_TOOL_PAIR","DURABLE_RUNTIME_PROMPT");
    private static final List<String> GROUP_ORDER=List.of("COMPLETED_TOOL_PAIR",
            "DURABLE_RUNTIME_PROMPT","DURABLE_ASSISTANT_RESULT","EVENT_FINAL",
            "PLAN_STEP","ATTEMPT","EVIDENCE","TOOL_OCCURRENCE","TOOL_RESULT",
            "ARTIFACT_REFERENCE","VERIFICATION_RESULT","REPAIR_ITERATION");
    private static final Set<String> REQUIRED_LATEST=Set.of("DURABLE_USER_INTENT",
            "DURABLE_RUNTIME_PROMPT","TOOL_RESULT","VERIFICATION_RESULT","REPAIR_ITERATION",
            "DURABLE_ASSISTANT_RESULT","EVENT_FINAL");

    /** Compatibility entry: returns the first safe group, while cost choice belongs to the Store. */
    public Selection select(List<HistorySegment> input,Budget budget){
        List<Selection> values=candidateSelections(input,budget);
        if(values.isEmpty())throw new PolicyFailure("COMPACTION_NOT_APPLICABLE");
        return values.getFirst();
    }

    /**
     * Returns only exact source groups that are independently safe to project. It never turns a
     * gap containing non-compactable or unresolved state into an implicit selected range.
     */
    public List<Selection> candidateSelections(List<HistorySegment> input,Budget budget){
        List<HistorySegment> segments=input.stream()
                .sorted(Comparator.comparingInt(HistorySegment::ordinal)).toList();
        long residual=validateAndResidual(segments,budget);
        var protectedOrdinals=new LinkedHashSet<Integer>();
        for(HistorySegment segment:segments)if(segment.compactability()==Compactability.NON_COMPACTABLE
                ||segment.factKind()==LedgerFactKind.UNKNOWN
                ||segment.factKind()==LedgerFactKind.UNRESOLVED)
            protectedOrdinals.add(segment.ordinal());
        for(String kind:REQUIRED_LATEST)findLatest(segments,kind)
                .ifPresent(value->protectedOrdinals.add(value.ordinal()));
        segments.stream().filter(value->value.compactability()==Compactability.COMPACTABLE
                        &&SUPPORTED.contains(value.kind()))
                .sorted(Comparator.comparingInt(HistorySegment::ordinal).reversed())
                .limit(MIN_RECENT_SUPPORTED)
                .forEach(value->protectedOrdinals.add(value.ordinal()));
        segments.stream().filter(value->"COMPLETED_TOOL_PAIR".equals(value.kind()))
                .sorted(Comparator.comparingInt(HistorySegment::ordinal).reversed())
                .limit(MIN_RECENT_TOOL_PAIRS)
                .forEach(value->protectedOrdinals.add(value.ordinal()));

        var selections=new ArrayList<Selection>();
        for(String kind:GROUP_ORDER){
            List<HistorySegment> eligible=segments.stream().filter(value->kind.equals(value.kind())
                            &&value.compactability()==Compactability.COMPACTABLE
                            &&!protectedOrdinals.contains(value.ordinal()))
                    .toList();
            for(List<Integer> group:contiguousGroups(eligible))
                selections.add(selection(group,segments,residual));
        }
        return List.copyOf(selections);
    }

    private static long validateAndResidual(List<HistorySegment> segments,Budget budget){
        for(HistorySegment segment:segments)if(segment.compactability()==Compactability.COMPACTABLE
                &&!SUPPORTED.contains(segment.kind()))throw new PolicyFailure(
                        "COMPACTION_UNSUPPORTED_SOURCE");
        if(budget.contextLimit()==null)throw new PolicyFailure("CONTEXT_BUDGET_UNKNOWN");
        long nonCompact=0,currentIntent=0;
        for(HistorySegment segment:segments)if(segment.compactability()==Compactability.NON_COMPACTABLE){
            if("TOOL_SCHEMA".equals(segment.kind()))continue;
            if("DURABLE_USER_INTENT".equals(segment.kind())||"PLAN_REQUEST".equals(segment.kind()))
                currentIntent=Math.addExact(currentIntent,segment.estimatedTokens());
            else nonCompact=Math.addExact(nonCompact,segment.estimatedTokens());
        }
        long residual=budget.contextLimit();
        residual=Math.subtractExact(residual,budget.toolSchemaEstimate());
        residual=Math.subtractExact(residual,ContextBudgetResolver.PROTOCOL_OVERHEAD_TOKENS);
        residual=Math.subtractExact(residual,budget.outputReserve());
        residual=Math.subtractExact(residual,budget.safetyMargin());
        residual=Math.subtractExact(residual,nonCompact);
        residual=Math.subtractExact(residual,currentIntent);
        if(residual<0)throw new PolicyFailure("CONTEXT_BUDGET_EXCEEDED");
        return residual;
    }

    private static List<List<Integer>> contiguousGroups(List<HistorySegment> values){
        var groups=new ArrayList<List<Integer>>();
        var current=new ArrayList<Integer>();
        int previous=-2;
        for(HistorySegment value:values){
            if(!current.isEmpty()&&value.ordinal()!=previous+1){
                groups.add(List.copyOf(current));current.clear();
            }
            current.add(value.ordinal());previous=value.ordinal();
        }
        if(!current.isEmpty())groups.add(List.copyOf(current));
        return List.copyOf(groups);
    }

    private static Selection selection(List<Integer> selected,List<HistorySegment> all,long residual){
        var selectedSet=Set.copyOf(selected);
        Set<Integer> retained=all.stream().map(HistorySegment::ordinal)
                .filter(value->!selectedSet.contains(value))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return new Selection(List.copyOf(selected),Set.copyOf(retained),residual);
    }

    private static java.util.Optional<HistorySegment> findLatest(List<HistorySegment> values,String kind){
        return values.stream().filter(value->kind.equals(value.kind()))
                .max(Comparator.comparingInt(HistorySegment::ordinal));
    }

    public record HistorySegment(int ordinal,String kind,Compactability compactability,
            int estimatedTokens,LedgerFactKind factKind){ }
    public record Selection(List<Integer> compactOrdinals,Set<Integer> retainedOrdinals,
            long historyResidualBudget){ }
    public static final class PolicyFailure extends RuntimeException{
        public PolicyFailure(String code){super(code);}
    }
}
