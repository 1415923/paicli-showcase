package com.paicli.interactive.view;

import java.time.Instant;
import java.util.Objects;

/** Typed row-local outcome for one durable Thread in /sessions. */
public record SessionRowProjectionResult(
        State state,
        String threadId,
        Instant updatedAt,
        boolean current,
        InteractiveSessionViewModel view,
        String diagnosticRef) {
    public SessionRowProjectionResult {
        Objects.requireNonNull(state);
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(updatedAt);
        if (state == State.PRESENT && view == null) throw new IllegalArgumentException("present row needs view");
        if (diagnosticRef != null && !diagnosticRef.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("unsafe diagnostic reference");
        }
    }
    public enum State { PRESENT, UNAVAILABLE, INTEGRITY_FAILURE, STALE, NOT_APPLICABLE }
}
