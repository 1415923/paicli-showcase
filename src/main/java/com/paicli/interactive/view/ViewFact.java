package com.paicli.interactive.view;

import java.time.Instant;
import java.util.Objects;

/** Immutable fact with explicit availability and provenance semantics. */
public record ViewFact<T>(State state, T value, Source source, Instant observedAt,
        Freshness freshness, String diagnosticRef) {
    public enum State {
        PRESENT, ABSENT, UNKNOWN, UNAVAILABLE, NOT_LOADED, NOT_EXPOSED,
        NOT_APPLICABLE, STALE
    }
    public enum Source { DURABLE, DERIVED, PREVIEW }
    public enum Freshness { CURRENT, RECENT, STALE, UNKNOWN }

    public ViewFact {
        Objects.requireNonNull(state);
        Objects.requireNonNull(source);
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(freshness);
        if (state == State.PRESENT && value == null) {
            throw new IllegalArgumentException("PRESENT fact requires a value");
        }
        if (state != State.PRESENT && state != State.STALE && value != null) {
            throw new IllegalArgumentException("Unavailable fact cannot carry a value");
        }
        if (diagnosticRef != null
                && !diagnosticRef.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("unsafe diagnostic reference");
        }
    }

    public static <T> ViewFact<T> present(T value, Source source, Instant at) {
        return new ViewFact<>(State.PRESENT, Objects.requireNonNull(value), source,
                at, Freshness.CURRENT, null);
    }

    public static <T> ViewFact<T> absent(Source source, Instant at) {
        return new ViewFact<>(State.ABSENT, null, source, at, Freshness.CURRENT, null);
    }

    public static <T> ViewFact<T> unavailable(Source source, Instant at, String ref) {
        return new ViewFact<>(State.UNAVAILABLE, null, source, at,
                Freshness.UNKNOWN, ref);
    }

    public static <T> ViewFact<T> state(State state, Source source, Instant at) {
        return new ViewFact<>(state, null, source, at, Freshness.UNKNOWN, null);
    }

    public boolean isPresent() { return state == State.PRESENT; }
}
