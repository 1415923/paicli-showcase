package com.paicli.benchmark;

import com.paicli.agent.AgentResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Machine-readable boundary between a task/domain outcome and harness validity. */
public record FormalDriverResult(
        OutcomeClass outcomeClass,
        TaskSuccess taskSuccess,
        String terminalReason,
        List<FailureCategory> failureCategories,
        boolean evidenceComplete,
        CampaignAction campaignAction) {

    public enum OutcomeClass { TASK_SUCCESS, TYPED_TASK_FAILURE, HARNESS_INVALID }
    public enum TaskSuccess { TRUE, FALSE, NOT_APPLICABLE }
    public enum CampaignAction { CONTINUE, STOP }

    public enum FailureCategory {
        EXTERNAL_ORACLE_FAILURE,
        WRONG_CHANGE,
        INCOMPLETE_RESULT_OR_FORMAL_RESULT_MISSING,
        PLAN_COMPATIBILITY_FAILURE,
        PROVIDER_PROTOCOL_FAILURE,
        PROVIDER_TRANSPORT_FAILURE,
        TOOL_OR_APPROVAL_FAILURE,
        VERIFICATION_FAILURE,
        REPAIR_EXHAUSTED,
        CONTEXT_INTEGRITY_FAILURE,
        RUNTIME_CONTRACT_ASSERTION_FAILURE,
        HARNESS_OR_EVIDENCE_INVALID
    }

    public FormalDriverResult {
        Objects.requireNonNull(outcomeClass);
        Objects.requireNonNull(taskSuccess);
        Objects.requireNonNull(terminalReason);
        Objects.requireNonNull(failureCategories);
        Objects.requireNonNull(campaignAction);
        failureCategories = List.copyOf(failureCategories);
        if (!terminalReason.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("terminalReason must be a typed code");
        }
        boolean harness = failureCategories.contains(
                FailureCategory.HARNESS_OR_EVIDENCE_INVALID);
        switch (outcomeClass) {
            case TASK_SUCCESS -> {
                if (taskSuccess != TaskSuccess.TRUE || !failureCategories.isEmpty()
                        || !evidenceComplete || campaignAction != CampaignAction.CONTINUE) {
                    throw new IllegalArgumentException("invalid TASK_SUCCESS boundary");
                }
            }
            case TYPED_TASK_FAILURE -> {
                if (taskSuccess != TaskSuccess.FALSE || failureCategories.isEmpty() || harness
                        || !evidenceComplete || campaignAction != CampaignAction.CONTINUE) {
                    throw new IllegalArgumentException("invalid TYPED_TASK_FAILURE boundary");
                }
            }
            case HARNESS_INVALID -> {
                if (taskSuccess != TaskSuccess.NOT_APPLICABLE
                        || !failureCategories.equals(List.of(
                        FailureCategory.HARNESS_OR_EVIDENCE_INVALID))
                        || evidenceComplete || campaignAction != CampaignAction.STOP) {
                    throw new IllegalArgumentException("invalid HARNESS_INVALID boundary");
                }
            }
        }
    }

    public static FormalDriverResult taskSucceeded() {
        return new FormalDriverResult(OutcomeClass.TASK_SUCCESS, TaskSuccess.TRUE,
                "TASK_COMPLETED", List.of(), true, CampaignAction.CONTINUE);
    }

    public static FormalDriverResult typedTaskFailure(String terminalReason,
            FailureCategory... categories) {
        return new FormalDriverResult(OutcomeClass.TYPED_TASK_FAILURE, TaskSuccess.FALSE,
                terminalReason, List.of(categories), true, CampaignAction.CONTINUE);
    }

    public static FormalDriverResult harnessInvalid(String terminalReason) {
        return new FormalDriverResult(OutcomeClass.HARNESS_INVALID,
                TaskSuccess.NOT_APPLICABLE, terminalReason,
                List.of(FailureCategory.HARNESS_OR_EVIDENCE_INVALID), false,
                CampaignAction.STOP);
    }

    public static FormalDriverResult fromAgentResult(AgentResult result) {
        Objects.requireNonNull(result);
        return switch (result.outcome()) {
            case SUCCESS -> taskSucceeded();
            case MAX_ITERATIONS -> typedTaskFailure("AGENT_MAX_ITERATIONS",
                    FailureCategory.INCOMPLETE_RESULT_OR_FORMAL_RESULT_MISSING);
            case PROVIDER_ERROR -> typedTaskFailure(
                    "PROVIDER_" + result.providerErrorKind().name(),
                    FailureCategory.PROVIDER_TRANSPORT_FAILURE);
        };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("outcomeClass", outcomeClass.name());
        value.put("taskSuccess", switch (taskSuccess) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_APPLICABLE -> "NOT_APPLICABLE";
        });
        value.put("terminalReason", terminalReason);
        value.put("failureCategories", failureCategories.stream().map(Enum::name).toList());
        value.put("evidenceComplete", evidenceComplete);
        value.put("campaignAction", campaignAction.name());
        return value;
    }
}
