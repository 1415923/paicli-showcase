package com.paicli.mcp.manifest;

public record McpConfigManifest(
        String manifestFormatVersion, String serverId, boolean enabled, String transport,
        String protocolVersionsJson, String configHash, String executableIdentityHash,
        String executableContentHash, String executableBasename,
        String workingDirectoryIdentityHash, String argsHash,
        String environmentReferencesJson, String toolAllowlistJson,
        int startupTimeoutMs, int requestTimeoutMs, int shutdownTimeoutMs,
        int maxPendingRequests, int maxStdoutLineBytes, int maxStderrBytes,
        int maxCatalogPages, int maxTools, int maxCatalogBytes,
        int maxSingleSchemaBytes, int maxOutputBytes,
        String approvalPolicyVersion, String sanitizerPolicyVersion,
        String schemaPolicyVersion, String canonicalJson) {
    @Override public String toString() { return "McpConfigManifest[serverId=" + serverId + ", configHash=" + configHash + "]"; }
}
