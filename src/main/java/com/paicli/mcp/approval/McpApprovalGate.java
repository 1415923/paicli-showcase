package com.paicli.mcp.approval;

import com.paicli.config.Config;
import com.paicli.safety.ApprovalPolicy;

import java.util.Objects;

public final class McpApprovalGate {
    private final ApprovalPolicy policy;
    private final McpApprovalChannel channel;
    public McpApprovalGate(ApprovalPolicy policy, McpApprovalChannel channel) {
        this.policy = Objects.requireNonNull(policy, "policy"); this.channel = channel;
    }
    public boolean approve(McpApprovalRequest request) {
        if (!Config.MODE_ON_REQUEST.equals(policy.getApprovalMode()) || channel == null) return false;
        return channel.requestApproval(request) == McpApprovalChannel.Decision.APPROVE;
    }
    public boolean consume(McpApprovalGrant grant, McpApprovalGrant.Binding binding,
            String task, String run, String occurrence) {
        return Config.MODE_ON_REQUEST.equals(policy.getApprovalMode()) && grant != null
                && grant.consume(binding, task, run, occurrence);
    }
}
