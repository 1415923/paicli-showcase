package com.paicli.interactive.session;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.Objects;

/** Process-local Saved Session selection. It is neither Task authority nor ownership. */
public record SessionBinding(State state, CanonicalId threadId, CanonicalId headTaskId,
                             String headTaskStatus, Instant refreshedAt) {
    public enum State {
        UNBOUND,
        BOUND_IDLE,
        BOUND_RECOVERABLE,
        BOUND_ACTIVE,
        BOUND_TERMINAL,
        BOUND_CONTINUATION_UNAVAILABLE
    }

    public SessionBinding {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(refreshedAt, "refreshedAt");
        if (state == State.UNBOUND) {
            if (threadId != null || headTaskId != null || headTaskStatus != null) {
                throw new IllegalArgumentException("Unbound Session cannot retain Thread facts");
            }
        } else {
            require(threadId, EntityFamily.THREAD);
            require(headTaskId, EntityFamily.TASK);
            if (headTaskStatus == null || headTaskStatus.isBlank()) {
                throw new IllegalArgumentException("Bound Session requires Head Task status");
            }
        }
    }

    public static SessionBinding unbound(Instant at) {
        return new SessionBinding(State.UNBOUND, null, null, null, at);
    }

    private static void require(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) {
            throw new IllegalArgumentException("Expected " + family + " canonical ID");
        }
    }
}
