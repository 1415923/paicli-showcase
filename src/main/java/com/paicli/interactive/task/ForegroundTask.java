package com.paicli.interactive.task;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Narrow foreground-shell view of one process-local execution handle.
 * Durable admission is completed only by the post-ownership TASK_STARTED boundary.
 */
public record ForegroundTask(
        String taskId,
        CompletableFuture<DurableAdmission> admission,
        CompletableFuture<TaskSubmissionResult> terminal) {

    public ForegroundTask {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("foreground task requires a Task ID");
        }
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(terminal, "terminal");
    }

    public record DurableAdmission(String taskId, String runId) {
        public DurableAdmission {
            if (taskId == null || taskId.isBlank()
                    || runId == null || runId.isBlank()) {
                throw new IllegalArgumentException(
                        "durable admission requires Task and Run identities");
            }
        }
    }
}
