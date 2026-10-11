package com.paicli.hook;

import java.util.Objects;

/** A validated, user-configured lifecycle hook. */
public record HookDefinition(
        String id,
        HookEvent event,
        String command,
        int timeoutSeconds,
        String failurePolicy,
        boolean enabled
) {
    public HookDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Hook id must not be blank");
        }
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("Hook id contains unsupported characters: " + id);
        }
        Objects.requireNonNull(event, "event");
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Hook command must not be blank");
        }
        if (command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Hook command contains a NUL character");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("Hook timeout_seconds must be between 1 and 300");
        }
        if (!"warn".equals(failurePolicy) && !"fail".equals(failurePolicy)) {
            throw new IllegalArgumentException("Hook failure_policy must be warn or fail");
        }
    }
}
