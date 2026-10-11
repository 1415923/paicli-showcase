package com.paicli.subagent;

/** Explicit runtime-owned target for optional deterministic code evidence. */
public record SubagentCodeTarget(
        TargetKind kind,
        String className,
        String methodName,
        boolean required,
        int maxContextChars
) {
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 12_000;
    public static final int MIN_CONTEXT_CHARS = 512;
    public static final int MAX_CONTEXT_CHARS = 65_536;

    public SubagentCodeTarget {
        if (kind == null) {
            throw new IllegalArgumentException("code target kind is required");
        }
        requireText(className, "className");
        if (kind == TargetKind.METHOD) {
            requireText(methodName, "methodName");
        } else if (methodName != null && !methodName.isBlank()) {
            throw new IllegalArgumentException("class target cannot include methodName");
        }
        if (maxContextChars < MIN_CONTEXT_CHARS
                || maxContextChars > MAX_CONTEXT_CHARS) {
            throw new IllegalArgumentException(
                    "maxContextChars must be between " + MIN_CONTEXT_CHARS
                            + " and " + MAX_CONTEXT_CHARS);
        }
    }

    public static SubagentCodeTarget forClass(String className, boolean required) {
        return new SubagentCodeTarget(
                TargetKind.CLASS, className, null, required,
                DEFAULT_MAX_CONTEXT_CHARS);
    }

    public static SubagentCodeTarget forMethod(
            String className, String methodName, boolean required) {
        return new SubagentCodeTarget(
                TargetKind.METHOD, className, methodName, required,
                DEFAULT_MAX_CONTEXT_CHARS);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    public enum TargetKind {
        CLASS,
        METHOD
    }
}
