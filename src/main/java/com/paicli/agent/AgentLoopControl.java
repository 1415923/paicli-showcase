package com.paicli.agent;

import com.paicli.model.ToolCall;
import com.paicli.safety.ApprovalDecision;
import com.paicli.runtime.CancellationToken;

public interface AgentLoopControl {
    AgentLoopControl NONE = new AgentLoopControl() { };

    default ApprovalDecision resolveApproval(
            String taskId, int iteration, ToolCall call, ApprovalDecision policyDecision) {
        return policyDecision;
    }

    default CancellationToken cancellationToken() {
        return CancellationToken.NONE;
    }

    default boolean hasPendingApproval() { return false; }
}
