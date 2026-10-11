package com.paicli.interactive.query;

import com.paicli.interactive.view.InteractiveSessionViewModel;

/** Typed read outcome; ABSENT and read failure are deliberately different. */
public record ViewModelReadResult(State state, InteractiveSessionViewModel value,
        String diagnosticRef) {
    public enum State { PRESENT, ABSENT, UNAVAILABLE, INTEGRITY_FAILURE }

    public ViewModelReadResult {
        if ((state == State.PRESENT) != (value != null)) {
            throw new IllegalArgumentException("ViewModel read state/value mismatch");
        }
        if (diagnosticRef != null
                && !diagnosticRef.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("unsafe ViewModel diagnostic reference");
        }
    }

    public static ViewModelReadResult present(InteractiveSessionViewModel value) {
        return new ViewModelReadResult(State.PRESENT,
                java.util.Objects.requireNonNull(value), null);
    }

    public static ViewModelReadResult absent() {
        return new ViewModelReadResult(State.ABSENT, null, null);
    }

    public static ViewModelReadResult unavailable(String ref) {
        return new ViewModelReadResult(State.UNAVAILABLE, null, ref);
    }
}
