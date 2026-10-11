package com.paicli.benchmark;

import java.util.Map;
import java.util.Set;

/** Closed machine mapping for the six frozen runtime-contract scenario outcomes. */
final class RuntimeContractDriverOutcome {
    private static final Map<String, String> TASK_CATEGORY = Map.of(
            "task-025", "CONTINUE",
            "task-026", "CONTINUE",
            "task-027", "COMPACT",
            "task-028", "COMPACT",
            "task-029", "RECOVERY",
            "task-030", "RECOVERY");

    private RuntimeContractDriverOutcome() { }

    static FormalDriverResult from(String taskId, String category,
            boolean runtimeContractSuccess, String terminalReason) {
        String frozenCategory = TASK_CATEGORY.get(taskId);
        if (frozenCategory == null || !frozenCategory.equals(category)) {
            throw new IllegalArgumentException("runtime task/category is outside frozen matrix");
        }
        if (runtimeContractSuccess) {
            if (!"TASK_COMPLETED".equals(terminalReason)) {
                throw new IllegalArgumentException("successful runtime outcome has another reason");
            }
            return FormalDriverResult.taskSucceeded();
        }
        Set<String> allowed = Set.of(category + "_ASSERTION_FAILURE");
        if ("CONTINUE".equals(category)) {
            allowed = Set.of("CONTINUE_ASSERTION_FAILURE", "FOLLOW_UP_NOT_ELIGIBLE");
        }
        if (!allowed.contains(terminalReason)) {
            throw new IllegalArgumentException("unknown runtime domain outcome");
        }
        return FormalDriverResult.typedTaskFailure(terminalReason,
                FormalDriverResult.FailureCategory.RUNTIME_CONTRACT_ASSERTION_FAILURE);
    }
}
