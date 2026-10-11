package com.paicli.subagent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record SubagentConfig(
        List<String> allowedTools,
        Set<SubagentCapability> capabilities,
        SubagentPermissionMode permissionMode,
        int maxTurns,
        int timeoutSeconds,
        int maxOutputChars,
        boolean enabled
) {
    public static final int MAX_ALLOWED_TOOLS = 16;
    public static final int MAX_TURNS = 20;
    public static final int MAX_TIMEOUT_SECONDS = 300;
    public static final int MAX_OUTPUT_CHARS = 65_536;

    public SubagentConfig {
        if (allowedTools == null) {
            allowedTools = List.of();
        } else {
            if (allowedTools.size() > MAX_ALLOWED_TOOLS) {
                throw new IllegalArgumentException("too many allowed tools");
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String tool : allowedTools) {
                if (tool == null || tool.isBlank()) {
                    throw new IllegalArgumentException("allowed tool names must not be blank");
                }
                if (!unique.add(tool)) {
                    throw new IllegalArgumentException("duplicate allowed tool: " + tool);
                }
            }
            allowedTools = List.copyOf(unique);
        }
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        if (permissionMode == null) {
            throw new IllegalArgumentException("permissionMode is required");
        }
        if (maxTurns < 1 || maxTurns > MAX_TURNS) {
            throw new IllegalArgumentException("maxTurns must be between 1 and " + MAX_TURNS);
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException(
                    "timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);
        }
        if (maxOutputChars < 1 || maxOutputChars > MAX_OUTPUT_CHARS) {
            throw new IllegalArgumentException(
                    "maxOutputChars must be between 1 and " + MAX_OUTPUT_CHARS);
        }
    }

    public SubagentConfig(
            List<String> allowedTools,
            SubagentPermissionMode permissionMode,
            int maxTurns,
            int timeoutSeconds,
            int maxOutputChars,
            boolean enabled) {
        this(allowedTools, Set.of(), permissionMode, maxTurns,
                timeoutSeconds, maxOutputChars, enabled);
    }
}
