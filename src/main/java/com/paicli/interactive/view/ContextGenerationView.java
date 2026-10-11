package com.paicli.interactive.view;
/** Read-only R3 context/budget/compaction projection; carries no mutation token. */
public record ContextGenerationView(String contextGenerationId,String operationKind,String lifecycle,
        long pointerVersion,String budgetState,String limitProvenance,Integer contextLimit,
        int estimatedTotal,String candidateId,String candidateLifecycle,String sourceContextGenerationId) {}
