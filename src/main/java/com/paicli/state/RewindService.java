package com.paicli.state;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public class RewindService {

    private static final long FILESYSTEM_TIME_MARGIN_MILLIS = 2_000L;

    private final FileSnapshotStore snapshots;
    private final FileSnapshotService.PathSecurity paths;
    private final Clock clock;
    private final TimestampRefresher timestampRefresher;

    public RewindService(Path workspace, FileSnapshotStore snapshots) throws IOException {
        this(workspace, snapshots, Clock.systemUTC(), RewindService::refreshTimestamp);
    }

    RewindService(Path workspace, FileSnapshotStore snapshots, Clock clock,
            TimestampRefresher timestampRefresher) throws IOException {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.paths = new FileSnapshotService.PathSecurity(workspace);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.timestampRefresher = Objects.requireNonNull(timestampRefresher,
                "timestampRefresher");
    }

    public RewindResult rewind(String snapshotId) throws IOException, SQLException {
        FileSnapshotStore.FileSnapshotRecord snapshot = snapshots.findById(snapshotId)
                .orElseThrow(() -> new RewindException("Snapshot not found: " + snapshotId));
        if (!FileSnapshotStore.STATUS_APPLIED.equals(snapshot.status())) {
            throw new RewindException("Snapshot is not APPLIED: " + snapshot.status());
        }

        Path target = paths.resolveStoredTarget(snapshot.filePath(), true);
        if (FileSnapshotService.isSensitivePath(target)) {
            throw new RewindException("Refusing to rewind sensitive file path: " + target.getFileName());
        }
        requireCurrentHash(snapshot, target);

        return switch (snapshot.operation()) {
            case FileSnapshotStore.OPERATION_CREATE -> rewindCreate(snapshot, target);
            case FileSnapshotStore.OPERATION_OVERWRITE, FileSnapshotStore.OPERATION_MODIFY ->
                    rewindExisting(snapshot, target);
            default -> throw new RewindException("Unsupported snapshot operation: " + snapshot.operation());
        };
    }

    private RewindResult rewindCreate(FileSnapshotStore.FileSnapshotRecord snapshot, Path target)
            throws IOException, SQLException {
        if (snapshot.originalExists()) {
            throw new RewindException("CREATE snapshot has inconsistent original_exists metadata");
        }
        Files.delete(target);
        snapshots.markRestored(snapshot.snapshotId());
        return new RewindResult(
                snapshot.snapshotId(),
                snapshot.filePath(),
                snapshot.operation(),
                true,
                true,
                "Deleted file created by snapshot"
        );
    }

    private RewindResult rewindExisting(FileSnapshotStore.FileSnapshotRecord snapshot, Path target)
            throws IOException, SQLException {
        if (!snapshot.originalExists() || snapshot.snapshotPath() == null || snapshot.sha256Before() == null) {
            throw new RewindException("Snapshot metadata is incomplete for restore");
        }
        Path payload = paths.resolveStoredPayload(snapshot.snapshotPath());
        String payloadHash = FileSnapshotService.sha256(payload);
        if (!snapshot.sha256Before().equals(payloadHash)) {
            throw new RewindException("Snapshot payload hash does not match sha256_before");
        }

        Instant rewindStarted = clock.instant();
        FileTime previousModified = Files.getLastModifiedTime(
                target, LinkOption.NOFOLLOW_LINKS);
        restoreAtomically(payload, target);
        verifyRestoredContent(snapshot, target);
        refreshRestoredTimestamp(target, previousModified, rewindStarted);
        verifyRestoredContent(snapshot, target);
        snapshots.markRestored(snapshot.snapshotId());
        return new RewindResult(
                snapshot.snapshotId(),
                snapshot.filePath(),
                snapshot.operation(),
                true,
                false,
                "Restored original file content"
        );
    }

    private void verifyRestoredContent(FileSnapshotStore.FileSnapshotRecord snapshot,
            Path target) throws IOException {
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !snapshot.sha256Before().equals(FileSnapshotService.sha256(target))) {
            throw new RewindException("Restored file does not match sha256_before");
        }
    }

    private void refreshRestoredTimestamp(Path target, FileTime previousModified,
            Instant rewindStarted) throws IOException {
        long lowerBound = Math.max(rewindStarted.toEpochMilli(),
                safeIncrement(previousModified.toMillis()));
        long requestedMillis = safeAdd(lowerBound, FILESYSTEM_TIME_MARGIN_MILLIS);
        FileTime observed;
        try {
            observed = timestampRefresher.refresh(
                    target, FileTime.fromMillis(requestedMillis));
        } catch (IOException | RuntimeException error) {
            throw timestampFailure(error);
        }
        if (observed == null
                || Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || observed.toMillis() < lowerBound) {
            throw timestampFailure(null);
        }
    }

    private static FileTime refreshTimestamp(Path target, FileTime requested)
            throws IOException {
        Files.setLastModifiedTime(target, requested);
        return Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS);
    }

    private static long safeIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
    }

    private static long safeAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static RewindException timestampFailure(Throwable cause) {
        RewindException error = new RewindException(
                "REWIND_TIMESTAMP_REFRESH_FAILED: restored source timestamp was not refreshed");
        if (cause != null) error.initCause(cause);
        return error;
    }

    private void requireCurrentHash(FileSnapshotStore.FileSnapshotRecord snapshot, Path target)
            throws IOException {
        if (snapshot.sha256After() == null || snapshot.sha256After().isBlank()) {
            throw new RewindException("Snapshot is missing sha256_after");
        }
        String currentHash = FileSnapshotService.sha256(target);
        if (!snapshot.sha256After().equals(currentHash)) {
            throw new RewindException("Current file hash does not match sha256_after");
        }
    }

    private void restoreAtomically(Path payload, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            throw new RewindException("Target file has no parent directory");
        }
        Path temporary = Files.createTempFile(parent, ".paicli-rewind-", ".tmp");
        try {
            Files.copy(payload, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public record RewindResult(
            String snapshotId,
            String filePath,
            String operation,
            boolean restored,
            boolean deleted,
            String message
    ) {
    }

    @FunctionalInterface
    interface TimestampRefresher {
        FileTime refresh(Path target, FileTime requested) throws IOException;
    }

    public static class RewindException extends IOException {
        public RewindException(String message) {
            super(message);
        }
    }
}
