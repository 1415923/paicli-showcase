package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record McpServerConfig(
        @JsonProperty("server_id") String serverId,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("transport") String transport,
        @JsonProperty("executable") Path executable,
        @JsonProperty("args") List<String> args,
        @JsonProperty("working_directory") Path workingDirectory,
        @JsonProperty("environment_references") List<McpEnvironmentReference> environmentReferences,
        @JsonProperty("limits") McpRuntimeLimits limits,
        @JsonProperty("allowed_tool_names") List<String> allowedToolNames,
        @JsonProperty("supported_protocol_versions") List<String> supportedProtocolVersions,
        @JsonProperty("artifact_paths") List<Path> artifactPaths) {
    public static final String PROTOCOL_VERSION = "2025-11-25";
    private static final Pattern SERVER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern TOOL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}");

    public McpServerConfig {
        artifactPaths = artifactPaths == null ? List.of() : List.copyOf(artifactPaths);
        if (artifactPaths.size() > 16) throw new IllegalArgumentException("too many artifact paths");
        if (serverId == null || !SERVER_ID.matcher(serverId).matches()) throw new IllegalArgumentException("invalid server_id");
        if (!"STDIO".equals(transport)) throw new IllegalArgumentException("transport must be STDIO");
        executable = executable == null ? null : executable.normalize();
        workingDirectory = workingDirectory == null ? null : workingDirectory.normalize();
        args = args == null ? List.of() : List.copyOf(args);
        if (args.stream().anyMatch(v -> v == null || v.isBlank() || v.indexOf('\0') >= 0)) throw new IllegalArgumentException("args must contain bounded non-blank values");
        rejectShellWrapper(executable, args);
        environmentReferences = environmentReferences == null ? List.of() : List.copyOf(environmentReferences);
        unique(environmentReferences.stream().map(McpEnvironmentReference::name).toList(), "environment references");
        limits = limits == null ? McpRuntimeLimits.defaults() : limits;
        allowedToolNames = allowedToolNames == null ? List.of() : List.copyOf(allowedToolNames);
        if (allowedToolNames.stream().anyMatch(v -> v == null || !TOOL.matcher(v).matches() || v.contains("*") || v.contains("?"))) throw new IllegalArgumentException("invalid exact tool allowlist");
        unique(allowedToolNames, "tool allowlist");
        supportedProtocolVersions = supportedProtocolVersions == null ? List.of(PROTOCOL_VERSION) : List.copyOf(supportedProtocolVersions);
        if (!supportedProtocolVersions.equals(List.of(PROTOCOL_VERSION))) throw new IllegalArgumentException("unsupported protocol version policy");
    }

    public McpServerConfig(String serverId, boolean enabled, String transport, Path executable,
            List<String> args, Path workingDirectory, List<McpEnvironmentReference> environmentReferences,
            McpRuntimeLimits limits, List<String> allowedToolNames, List<String> supportedProtocolVersions) {
        this(serverId, enabled, transport, executable, args, workingDirectory, environmentReferences,
                limits, allowedToolNames, supportedProtocolVersions, List.of());
    }

    private static void unique(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) throw new IllegalArgumentException("duplicate " + field);
    }

    private static void rejectShellWrapper(Path executable, List<String> args) {
        if (executable == null) return;
        String name = executable.getFileName().toString().toLowerCase();
        Set<String> shells = Set.of("cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe", "bash", "sh");
        if (shells.contains(name) || name.endsWith(".cmd") || name.endsWith(".bat") || name.endsWith(".ps1") || args.stream().anyMatch(v -> v.equalsIgnoreCase("/c") || v.equalsIgnoreCase("-command") || v.equals("-c"))) {
            throw new IllegalArgumentException("shell wrappers are not allowed");
        }
    }

    @Override public String toString() { return "McpServerConfig[serverId=" + serverId + ", enabled=" + enabled + ", transport=" + transport + "]"; }
}
