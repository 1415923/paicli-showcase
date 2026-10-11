package com.paicli.identity;

/** Explicit selector scope. It is query context and is never persisted as identity authority. */
public record ShortIdScope(String workspaceIdentity, CanonicalId threadId) {
    public ShortIdScope {
        if (workspaceIdentity == null || workspaceIdentity.isBlank()) {
            throw new IllegalArgumentException("Workspace selector scope is required");
        }
        if (threadId != null && threadId.family() != EntityFamily.THREAD) {
            throw new IllegalArgumentException("Thread selector scope requires a Thread ID");
        }
    }

    public static ShortIdScope workspace(String workspaceIdentity) {
        return new ShortIdScope(workspaceIdentity, null);
    }
}
