package com.paicli.safety;

/**
 * Thrown when a safety policy check fails.
 */
public class SafetyViolationException extends Exception {

    private final ViolationType violationType;
    private final String detail;

    public enum ViolationType {
        PATH_TRAVERSAL,
        PATH_OUTSIDE_WORKSPACE,
        DANGEROUS_COMMAND
    }

    public SafetyViolationException(ViolationType violationType, String detail) {
        super(formatMessage(violationType, detail));
        this.violationType = violationType;
        this.detail = detail;
    }

    public ViolationType violationType() {
        return violationType;
    }

    public String detail() {
        return detail;
    }

    private static String formatMessage(ViolationType type, String detail) {
        return "[" + type + "] " + detail;
    }
}
