package com.paicli.mcp.manifest;

public final class McpManifestComparator {
    public McpManifestDrift compare(McpConfigManifest oldValue, McpConfigManifest current) {
        if (oldValue.configHash().equals(current.configHash())) return McpManifestDrift.MATCH;
        if (!oldValue.serverId().equals(current.serverId()) || oldValue.enabled() != current.enabled() || !oldValue.transport().equals(current.transport())) return McpManifestDrift.SERVER_CONFIG_DRIFT;
        if (!oldValue.executableIdentityHash().equals(current.executableIdentityHash()) || !oldValue.executableContentHash().equals(current.executableContentHash())) return McpManifestDrift.EXECUTABLE_DRIFT;
        if (!oldValue.argsHash().equals(current.argsHash())) return McpManifestDrift.ARGUMENTS_DRIFT;
        if (!oldValue.workingDirectoryIdentityHash().equals(current.workingDirectoryIdentityHash())) return McpManifestDrift.WORKING_DIRECTORY_DRIFT;
        if (!oldValue.environmentReferencesJson().equals(current.environmentReferencesJson())) return McpManifestDrift.ENVIRONMENT_REFERENCE_DRIFT;
        if (!oldValue.toolAllowlistJson().equals(current.toolAllowlistJson())) return McpManifestDrift.TOOL_ALLOWLIST_DRIFT;
        if (!oldValue.protocolVersionsJson().equals(current.protocolVersionsJson())) return McpManifestDrift.PROTOCOL_POLICY_DRIFT;
        if (!oldValue.approvalPolicyVersion().equals(current.approvalPolicyVersion())) return McpManifestDrift.APPROVAL_POLICY_DRIFT;
        if (!oldValue.sanitizerPolicyVersion().equals(current.sanitizerPolicyVersion())) return McpManifestDrift.SANITIZER_POLICY_DRIFT;
        if (!oldValue.schemaPolicyVersion().equals(current.schemaPolicyVersion())) return McpManifestDrift.SCHEMA_POLICY_DRIFT;
        return McpManifestDrift.LIMITS_DRIFT;
    }
}
