package com.paicli.worker;

import java.util.List;
import java.util.regex.Pattern;

/** Immutable limits and instruction for one writable, isolated WorktreeWorker. */
public record WorktreeWorkerConfig(
        String name,
        String systemInstruction,
        int maxTurns,
        int timeoutSeconds,
        int maxOutputChars,
        boolean enabled
) {
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_INSTRUCTION_CHARS = 16_384;
    public static final int MAX_TURNS = 50;
    public static final int MAX_TIMEOUT_SECONDS = 600;
    public static final int MAX_OUTPUT_CHARS = 65_536;
    public static final List<String> CAPABILITIES = List.of(
            "git_diff", "grep", "list_files", "read_file", "write_file");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    public WorktreeWorkerConfig {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_CHARS
                || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Worker name is invalid");
        }
        if (systemInstruction == null || systemInstruction.isBlank()
                || systemInstruction.length() > MAX_INSTRUCTION_CHARS) {
            throw new IllegalArgumentException("Worker system instruction is invalid or too large");
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
}
