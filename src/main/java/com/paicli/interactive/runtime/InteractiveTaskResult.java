package com.paicli.interactive.runtime;

import com.paicli.interactive.task.TaskSubmissionResult;

public record InteractiveTaskResult(
        String taskId, String runId, TaskSubmissionResult.Status status,
        String code, String boundedMessage) {
    public InteractiveTaskResult {
        if (taskId == null || taskId.isBlank() || status == null
                || code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")
                || boundedMessage == null || boundedMessage.length() > 512) {
            throw new IllegalArgumentException("invalid interactive task result");
        }
    }
}
