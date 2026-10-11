package com.paicli.agent;

import com.paicli.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Payload-free durable classification for a rejected whole-batch Tool preflight.
 * Raw arguments, paths, commands and free-text Tool errors are deliberately absent.
 */
public record SafePreflightRejectionDiagnostic(
        String runId,
        String toolName,
        int batchOrdinal,
        ToolRegistry.PreflightFailureKind failureKind,
        SafeReason safeReason,
        String preflightCode) {

    public static final String SCHEMA_VERSION = "SAFE_TYPED_PREFLIGHT_REJECTION_V1";
    private static final String UNAVAILABLE = "UNAVAILABLE";

    public SafePreflightRejectionDiagnostic {
        runId = safeIdentifier(runId);
        toolName = safeIdentifier(toolName);
        preflightCode = safeCode(preflightCode);
        if (batchOrdinal < 1 || failureKind == null || safeReason == null) {
            throw new IllegalArgumentException("Invalid safe preflight diagnostic");
        }
    }

    public static SafePreflightRejectionDiagnostic from(String runId, String toolName,
            int batchOrdinal, ToolRegistry.PreflightResult result) {
        Objects.requireNonNull(result, "result");
        if (result.accepted() || result.failureKind() == null) {
            throw new IllegalArgumentException("A rejected typed preflight result is required");
        }
        return new SafePreflightRejectionDiagnostic(runId, toolName, batchOrdinal,
                result.failureKind(), SafeReason.from(result), result.code());
    }

    /** Stable token-only projection suitable for the existing durable Event content field. */
    public String durableText() {
        return String.join(" ", SCHEMA_VERSION,
                "run_id=" + runId,
                "tool_name=" + toolName,
                "batch_ordinal=" + batchOrdinal,
                "failure_kind=" + failureKind.name(),
                "safe_reason=" + safeReason.name(),
                "preflight_code=" + preflightCode);
    }

    /** Typed read-back for diagnostics and closeout; ignores unrelated Event content. */
    public static Optional<SafePreflightRejectionDiagnostic> parse(String content) {
        if (content == null) return Optional.empty();
        int start = content.indexOf(SCHEMA_VERSION + " ");
        if (start < 0) return Optional.empty();
        String[] tokens = content.substring(start).strip().split("\\s+");
        if (tokens.length != 7 || !SCHEMA_VERSION.equals(tokens[0])) return Optional.empty();
        Map<String, String> fields = new LinkedHashMap<>();
        for (int index = 1; index < tokens.length; index++) {
            int separator = tokens[index].indexOf('=');
            if (separator < 1 || separator == tokens[index].length() - 1) {
                return Optional.empty();
            }
            fields.put(tokens[index].substring(0, separator),
                    tokens[index].substring(separator + 1));
        }
        try {
            if (!fields.keySet().equals(java.util.Set.of("run_id", "tool_name",
                    "batch_ordinal", "failure_kind", "safe_reason", "preflight_code"))) {
                return Optional.empty();
            }
            return Optional.of(new SafePreflightRejectionDiagnostic(
                    fields.get("run_id"), fields.get("tool_name"),
                    Integer.parseInt(fields.get("batch_ordinal")),
                    ToolRegistry.PreflightFailureKind.valueOf(fields.get("failure_kind")),
                    SafeReason.valueOf(fields.get("safe_reason")),
                    fields.get("preflight_code")));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,128}")
                ? value : UNAVAILABLE;
    }

    private static String safeCode(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,128}")
                ? value : "UNCLASSIFIED_PREFLIGHT_REJECTION";
    }

    public enum SafeReason {
        REGISTRATION_REJECTED,
        ARGUMENT_SCHEMA_REJECTED,
        POLICY_REJECTED,
        PROTECTED_CONTROL_PATH_REJECTED,
        APPROVAL_REQUIRED,
        AUTHORITY_REJECTED,
        INTEGRITY_REJECTED,
        OTHER_SAFE_REJECTION;

        private static SafeReason from(ToolRegistry.PreflightResult result) {
            String code = result.code();
            if (result.failureKind() == ToolRegistry.PreflightFailureKind.APPROVAL_REQUIRED) {
                return APPROVAL_REQUIRED;
            }
            if ("TOOL_CALL_MISSING".equals(code) || "TOOL_NOT_REGISTERED".equals(code)) {
                return REGISTRATION_REJECTED;
            }
            if (code != null && code.startsWith("TOOL_ARGUMENT_SCHEMA_")) {
                return ARGUMENT_SCHEMA_REJECTED;
            }
            if ("TOOL_AUTHORITY_PROJECTION_FAILED".equals(code)) {
                return AUTHORITY_REJECTED;
            }
            if (result.failureKind() == ToolRegistry.PreflightFailureKind.POLICY_DENIED) {
                // ToolResult.error is free text. Do not parse it to infer path subclasses.
                return POLICY_REJECTED;
            }
            if (result.failureKind() == ToolRegistry.PreflightFailureKind.INTEGRITY_FAILURE) {
                return INTEGRITY_REJECTED;
            }
            return OTHER_SAFE_REJECTION;
        }
    }
}
