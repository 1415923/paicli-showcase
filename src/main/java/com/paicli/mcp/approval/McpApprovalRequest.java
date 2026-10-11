package com.paicli.mcp.approval;

public record McpApprovalRequest(String serverId, String originalToolName, String exposedToolName,
                                 String untrustedDescription, String boundedArgumentsSummary,
                                 String manifestHash, String catalogHash, String schemaHash) { }
