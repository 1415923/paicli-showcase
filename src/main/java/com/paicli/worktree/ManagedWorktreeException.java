package com.paicli.worktree;

/** Typed fail-closed error raised by the managed worktree boundary. */
public final class ManagedWorktreeException extends Exception {
    private final Code code;

    public ManagedWorktreeException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ManagedWorktreeException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        UNSUPPORTED_BACKEND,
        INVALID_REQUEST,
        INVALID_REPOSITORY,
        UNSUPPORTED_REPOSITORY,
        REPOSITORY_BUSY,
        PATH_UNSAFE,
        COLLISION,
        BASE_DRIFT,
        GIT_NOT_AVAILABLE,
        GIT_NON_ZERO,
        GIT_TIMEOUT,
        GIT_ERROR,
        POST_CREATE_VERIFICATION_FAILED,
        PERSISTENCE_FAILED,
        RECONCILIATION_MISMATCH
    }
}
