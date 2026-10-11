package com.paicli.interactive.view;

import java.util.List;

public record OperationalStatusView(
        Status primary,
        List<String> warnings,
        List<String> allowedActionHints,
        List<String> prohibitedActionHints,
        ViewFact<FailureDomain> failureDomain,
        String userPrompt,
        String decisionRuleVersion) {

    public enum Status {
        BLOCKED_INTEGRITY,
        WAITING_FOR_CONFIRMATION,
        RECOVERY_REQUIRED,
        WAITING_FOR_USER,
        CHILD_INITIAL_RUN_PENDING,
        EXECUTING,
        WAITING_FOR_APPROVAL,
        VERIFYING,
        REPAIRING,
        FOLLOW_UP_PENDING,
        COMPLETED,
        PROVIDER_DEGRADED,
        READY,
        IDLE,
        FAILED
    }

    public OperationalStatusView {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        allowedActionHints = allowedActionHints == null
                ? List.of() : List.copyOf(allowedActionHints);
        prohibitedActionHints = prohibitedActionHints == null
                ? List.of() : List.copyOf(prohibitedActionHints);
    }
}
