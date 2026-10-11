package com.paicli.safety;

import java.time.Instant;

/**
 * Structured result of an approval decision.
 */
public record ApprovalDecision(
        Decision decision,
        RiskLevel riskLevel,
        String reason,
        String toolName,
        String taskId,
        Instant timestamp
) {

    public enum Decision {
        ALLOW,
        DENY,
        NEEDS_APPROVAL
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    public static ApprovalDecision allow(String toolName, RiskLevel risk, String taskId, String reason) {
        return new ApprovalDecision(Decision.ALLOW, risk, reason, toolName, taskId, Instant.now());
    }

    public static ApprovalDecision deny(String toolName, RiskLevel risk, String taskId, String reason) {
        return new ApprovalDecision(Decision.DENY, risk, reason, toolName, taskId, Instant.now());
    }

    public static ApprovalDecision needsApproval(String toolName, RiskLevel risk, String taskId, String reason) {
        return new ApprovalDecision(Decision.NEEDS_APPROVAL, risk, reason, toolName, taskId, Instant.now());
    }

    public boolean isAllowed() {
        return decision == Decision.ALLOW;
    }
}
