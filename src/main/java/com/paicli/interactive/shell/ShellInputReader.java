package com.paicli.interactive.shell;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public interface ShellInputReader extends AutoCloseable {
    String readLine(String prompt) throws InputInterruptedException;

    /**
     * Reads using the coordinator-owned prompt generation. Legacy readers can
     * ignore generations; interactive readers override this to reject stale
     * prompt refreshes.
     */
    default String readLine(String prompt, long generation) throws InputInterruptedException {
        return readLine(prompt);
    }
    default String readLine(String prompt, PromptIdentity identity)
            throws InputInterruptedException {
        return readLine(prompt, identity.generation());
    }

    /** Reads a no-echo secret without adding it to terminal history. */
    default char[] readSecret(String prompt,PromptIdentity identity)
            throws InputInterruptedException { return null; }

    /**
     * Reads one foreground approval choice while the supplied ownership remains
     * valid. Interactive implementations must poll on the calling thread so a
     * terminal Task or expired approval can end the read without a second stdin
     * owner or a synthetic terminal signal.
     */
    default ForegroundReadResult readForegroundChoice(String prompt,
            PromptIdentity identity, BooleanSupplier ownershipValid)
            throws InputInterruptedException {
        Objects.requireNonNull(ownershipValid, "ownershipValid");
        if (!ownershipValid.getAsBoolean()) return ForegroundReadResult.invalidated();
        String value = readLine(prompt, identity);
        if (!ownershipValid.getAsBoolean()) return ForegroundReadResult.invalidated();
        return value == null ? ForegroundReadResult.eof()
                : ForegroundReadResult.value(value);
    }

    /**
     * Polls the sole foreground input owner for an application action without
     * restoring the main composer. Plain/scripted readers intentionally do not
     * consume queued lines while a Task is running.
     */
    default ForegroundReadResult pollForegroundAction(
            PromptIdentity identity, BooleanSupplier ownershipValid)
            throws InputInterruptedException {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(ownershipValid, "ownershipValid");
        return ownershipValid.getAsBoolean()
                ? ForegroundReadResult.none()
                : ForegroundReadResult.invalidated();
    }
    default void runtimeStateChanged(String prompt) { }

    /**
     * Requests a prompt refresh for a specific generation. The default keeps
     * existing non-interactive readers source-compatible.
     */
    default void runtimeStateChanged(String prompt, long generation) {
        runtimeStateChanged(prompt);
    }
    default void runtimeStateChanged(String prompt, PromptIdentity identity) {
        runtimeStateChanged(prompt, identity.generation());
    }
    default boolean isReading() { return false; }
    /** Clears only the current terminal's navigation history. */
    default boolean clearNavigationHistory() { return true; }
    @Override default void close() { }

    record PromptIdentity(String sessionId, String taskId, long generation,
            String inputState) {
        public PromptIdentity {
            if (sessionId == null || sessionId.isBlank()
                    || generation < 0 || inputState == null || inputState.isBlank()) {
                throw new IllegalArgumentException("invalid prompt identity");
            }
        }
    }

    enum ForegroundReadStatus { VALUE, CANCEL_TASK, NONE, INVALIDATED, EOF }

    record ForegroundReadResult(ForegroundReadStatus status, String value) {
        public ForegroundReadResult {
            Objects.requireNonNull(status, "status");
            if (status == ForegroundReadStatus.VALUE && value == null) {
                throw new IllegalArgumentException("foreground input value is required");
            }
            if (status != ForegroundReadStatus.VALUE && value != null) {
                throw new IllegalArgumentException("non-value foreground input must be empty");
            }
        }

        public static ForegroundReadResult value(String value) {
            return new ForegroundReadResult(ForegroundReadStatus.VALUE,
                    Objects.requireNonNull(value, "value"));
        }

        public static ForegroundReadResult invalidated() {
            return new ForegroundReadResult(ForegroundReadStatus.INVALIDATED, null);
        }

        public static ForegroundReadResult cancelTask() {
            return new ForegroundReadResult(ForegroundReadStatus.CANCEL_TASK, null);
        }

        public static ForegroundReadResult none() {
            return new ForegroundReadResult(ForegroundReadStatus.NONE, null);
        }

        public static ForegroundReadResult eof() {
            return new ForegroundReadResult(ForegroundReadStatus.EOF, null);
        }
    }
}
