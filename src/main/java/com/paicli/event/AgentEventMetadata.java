package com.paicli.event;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record AgentEventMetadata(
        AgentEventType eventType,
        String sessionId,
        String taskId,
        String runId,
        long sequence,
        Instant timestamp) {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    public AgentEventMetadata {
        Objects.requireNonNull(eventType, "eventType");
        sessionId = requireId(sessionId, "sessionId");
        taskId = optionalId(taskId, "taskId");
        runId = optionalId(runId, "runId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    private static String requireId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()
                || ".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value;
    }

    private static String optionalId(String value, String field) {
        return value == null ? null : requireId(value, field);
    }
}
