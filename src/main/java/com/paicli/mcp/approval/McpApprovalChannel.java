package com.paicli.mcp.approval;

@FunctionalInterface
public interface McpApprovalChannel {
    Decision requestApproval(McpApprovalRequest request);
    enum Decision { APPROVE, DENY }
}
