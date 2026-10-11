package com.paicli.benchmark;

import com.paicli.agent.AgentLoopControl;
import com.paicli.model.ToolCall;
import com.paicli.safety.ApprovalDecision;

import java.util.Objects;
import java.util.function.BiPredicate;

/** Applies the benchmark's predeclared approval decisions identically to either runtime. */
public final class PredeclaredApprovalControl implements AgentLoopControl {
    private final BiPredicate<Integer, ToolCall> allowed;

    public PredeclaredApprovalControl(BiPredicate<Integer, ToolCall> allowed) {
        this.allowed = Objects.requireNonNull(allowed, "allowed");
    }

    @Override
    public ApprovalDecision resolveApproval(String taskId, int iteration, ToolCall call,
            ApprovalDecision policyDecision) {
        if (policyDecision.decision() != ApprovalDecision.Decision.NEEDS_APPROVAL) {
            return policyDecision;
        }
        return allowed.test(iteration, call)
                ? ApprovalDecision.allow(call.name(), policyDecision.riskLevel(),
                        policyDecision.taskId(), "benchmark predeclared approval")
                : ApprovalDecision.deny(call.name(), policyDecision.riskLevel(),
                        policyDecision.taskId(), "benchmark predeclared rejection");
    }
}
