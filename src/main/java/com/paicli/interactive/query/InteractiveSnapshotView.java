package com.paicli.interactive.query;

import java.util.Objects;

/** Snapshot metadata only; payload paths and content are intentionally absent. */
public record InteractiveSnapshotView(
        String snapshotId,
        String safeSnapshotId,
        String taskId,
        String createdAt,
        String relativePath,
        String reason,
        boolean currentHashMatches,
        boolean rewindable,
        String status,
        String restoresTo,
        String eligibilityCode) {
    public InteractiveSnapshotView(String snapshotId, String safeSnapshotId,
            String taskId, String createdAt, String relativePath, String reason,
            boolean currentHashMatches, boolean rewindable, String status,
            String restoresTo) {
        this(snapshotId, safeSnapshotId, taskId, createdAt, relativePath, reason,
                currentHashMatches, rewindable, status, restoresTo,
                rewindable ? "REWINDABLE" : "SNAPSHOT_NOT_APPLIED");
    }
    public InteractiveSnapshotView(String snapshotId, String safeSnapshotId,
            String taskId, String createdAt, String relativePath, String reason,
            boolean currentHashMatches, boolean rewindable, String status) {
        this(snapshotId, safeSnapshotId, taskId, createdAt, relativePath, reason,
                currentHashMatches, rewindable, status, "previous content",
                rewindable ? "REWINDABLE" : "SNAPSHOT_NOT_APPLIED");
    }
    public InteractiveSnapshotView {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(safeSnapshotId, "safeSnapshotId");
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(restoresTo, "restoresTo");
        Objects.requireNonNull(eligibilityCode, "eligibilityCode");
    }
}
