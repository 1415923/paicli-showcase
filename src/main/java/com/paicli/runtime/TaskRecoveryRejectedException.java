package com.paicli.runtime;

/** Safe typed bridge from Task Recovery admission to the interactive terminal result. */
public final class TaskRecoveryRejectedException extends IllegalStateException {
    private final String code;

    public TaskRecoveryRejectedException(String code, String message) {
        super(message);
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid Task Recovery rejection code");
        }
        this.code = code;
    }

    public String code() {
        return code;
    }
}
