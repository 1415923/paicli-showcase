package com.paicli.interactive.query;

import java.util.Objects;

/** Bounded task facts for interactive selection and rendering. */
public record InteractiveTaskView(
        String taskId,
        String safeTaskId,
        String updatedAt,
        String summary,
        String provider,
        String model,
        String status,
        String phase,
        boolean resumable,
        int snapshotCount,
        String verificationStatus) {
    public InteractiveTaskView {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(safeTaskId, "safeTaskId");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(verificationStatus, "verificationStatus");
    }
}
