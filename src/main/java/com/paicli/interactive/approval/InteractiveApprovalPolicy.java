package com.paicli.interactive.approval;

import com.paicli.config.Config;
import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.ApprovalPolicy;

import java.util.Objects;
import java.util.function.Supplier;

public final class InteractiveApprovalPolicy extends ApprovalPolicy {
    private final Supplier<String> mode;
    private final String taskId;

    public InteractiveApprovalPolicy(Supplier<String> mode, String taskId) {
        super(mode, taskId);
        this.mode = Objects.requireNonNull(mode, "mode");
        this.taskId = taskId;
    }

    @Override
    public ApprovalDecision evaluate(String toolName, boolean overwrite) {
        if (!"write_file".equals(toolName)) return super.evaluate(toolName, overwrite);
        ApprovalDecision.RiskLevel risk = classifyRisk(toolName, overwrite);
        return switch (mode.get()) {
            case Config.MODE_ON_REQUEST -> ApprovalDecision.needsApproval(
                    toolName, risk, taskId, "interactive on-request mode requires write approval");
            case Config.MODE_WORKSPACE_WRITE -> ApprovalDecision.allow(
                    toolName, risk, taskId, "interactive workspace-write permits bounded workspace edit");
            case Config.MODE_PLAN, Config.MODE_READ_ONLY, Config.MODE_DENY ->
                    ApprovalDecision.deny(toolName, risk, taskId,
                            mode.get() + " mode denies file writes");
            default -> ApprovalDecision.deny(toolName, risk, taskId,
                    "unknown interactive approval mode");
        };
    }
}
