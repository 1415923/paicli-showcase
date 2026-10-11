package com.paicli.demo.runtime;

public enum CoreDemoExitCode {
    COMPLETED(0),
    USAGE(2),
    BASELINE_INVALID(3),
    EXECUTION_FAILED(4),
    VERIFICATION_FAILED(5),
    RESUME_REWIND_FAILED(6),
    ARTIFACT_FAILED(7),
    INTERNAL_ERROR(8);

    private final int code;
    CoreDemoExitCode(int code) { this.code = code; }
    public int code() { return code; }
}
