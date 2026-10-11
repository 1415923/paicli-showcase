package com.paicli.subagent;

import java.util.regex.Pattern;

public record SubagentDefinition(
        String name,
        SubagentKind kind,
        String systemInstruction,
        SubagentConfig config
) {
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_INSTRUCTION_CHARS = 16_384;

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public SubagentDefinition {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_CHARS
                || !VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name must be 1-64 characters using letters, digits, '.', '_' or '-'");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (systemInstruction == null || systemInstruction.isBlank()) {
            throw new IllegalArgumentException("systemInstruction is required");
        }
        if (systemInstruction.length() > MAX_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException("systemInstruction is too large");
        }
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        if (!config.capabilities().isEmpty() && kind != SubagentKind.VERIFIER) {
            throw new SecurityException(
                    "configured verification capability is reserved for VERIFIER");
        }
    }
}
