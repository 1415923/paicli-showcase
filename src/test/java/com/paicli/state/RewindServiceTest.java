package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RewindServiceTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private DatabaseManager databaseManager;
    private TaskStore tasks;
    private FileSnapshotStore snapshots;
    private FileSnapshotService snapshotService;
    private RewindService rewindService;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        databaseManager = new DatabaseManager(workspace.resolve(".paicli/state"));
        Connection connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        snapshots = new FileSnapshotStore(connection);
        snapshotService = new FileSnapshotService(workspace, snapshots);
        rewindService = new RewindService(workspace, snapshots);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void rewindsCreateByDeletingFileButKeepsParentDirectory() throws Exception {
        createTask("task-create");
        Path parent = workspace.resolve("generated");
        Files.createDirectories(parent);
        Path target = parent.resolve("new.txt");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-create", null, Path.of("generated/new.txt"));
        Files.writeString(target, "generated content");
        snapshotService.markAppliedAfterWrite(snapshotId, Path.of("generated/new.txt"));

        RewindService.RewindResult result = rewindService.rewind(snapshotId);

        assertTrue(result.restored());
        assertTrue(result.deleted());
        assertFalse(Files.exists(target));
        assertTrue(Files.isDirectory(parent));
        assertEquals(FileSnapshotStore.STATUS_RESTORED,
                snapshots.findById(snapshotId).orElseThrow().status());
    }

    @Test
    void rewindsOverwriteByRestoringOriginalBytes() throws Exception {
        createTask("task-overwrite");
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "original");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-overwrite", "call-1", Path.of("existing.txt"));
        Files.writeString(target, "replacement");
        snapshotService.markAppliedAfterWrite(snapshotId, Path.of("existing.txt"));

        RewindService.RewindResult result = rewindService.rewind(snapshotId);

        assertTrue(result.restored());
        assertFalse(result.deleted());
        assertEquals("original", Files.readString(target));
        assertEquals(FileSnapshotStore.STATUS_RESTORED,
                snapshots.findById(snapshotId).orElseThrow().status());
    }

    @Test
    void restoredSourceIsNewerThanIncrementalBuildArtifactWithoutDeletingArtifact()
            throws Exception {
        createTask("task-build-invalidation");
        Path target = workspace.resolve("src/main/java/demo/Sample.java");
        Path artifact = workspace.resolve("target/classes/demo/Sample.class");
        Files.createDirectories(target.getParent());
        Files.createDirectories(artifact.getParent());
        Files.writeString(target, "before");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-build-invalidation", null,
                Path.of("src/main/java/demo/Sample.java"));
        Files.writeString(target, "after");
        snapshotService.markAppliedAfterWrite(snapshotId,
                Path.of("src/main/java/demo/Sample.java"));

        Instant rewindStart = Instant.parse("2030-01-01T00:00:10Z");
        Files.setLastModifiedTime(target,
                FileTime.from(Instant.parse("2030-01-01T00:00:00Z")));
        Files.write(artifact, new byte[] { 1, 2, 3 });
        FileTime artifactTime = FileTime.from(
                Instant.parse("2030-01-01T00:00:10.500Z"));
        Files.setLastModifiedTime(artifact, artifactTime);
        byte[] artifactBytes = Files.readAllBytes(artifact);

        RewindService service = new RewindService(workspace, snapshots,
                Clock.fixed(rewindStart, ZoneOffset.UTC),
                (file, requested) -> {
                    Files.setLastModifiedTime(file, requested);
                    return Files.getLastModifiedTime(file);
                });
        service.rewind(snapshotId);

        assertEquals("before", Files.readString(target));
        assertTrue(Files.getLastModifiedTime(target).compareTo(artifactTime) > 0);
        assertArrayEquals(artifactBytes, Files.readAllBytes(artifact));
        assertEquals(artifactTime, Files.getLastModifiedTime(artifact));

        FileTime restoredTime = Files.getLastModifiedTime(target);
        assertThrows(RewindService.RewindException.class,
                () -> service.rewind(snapshotId));
        assertEquals(restoredTime, Files.getLastModifiedTime(target));
    }

    @Test
    void timestampRefreshFailureDoesNotPersistSuccessfulRewind() throws Exception {
        createTask("task-timestamp-failure");
        Path target = workspace.resolve("timestamp.txt");
        Files.writeString(target, "before");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-timestamp-failure", null, Path.of("timestamp.txt"));
        Files.writeString(target, "after");
        snapshotService.markAppliedAfterWrite(snapshotId, Path.of("timestamp.txt"));
        RewindService service = new RewindService(workspace, snapshots,
                Clock.systemUTC(), (file, requested) -> {
                    throw new IOException("simulated timestamp failure");
                });

        RewindService.RewindException error = assertThrows(
                RewindService.RewindException.class, () -> service.rewind(snapshotId));

        assertTrue(error.getMessage().contains("REWIND_TIMESTAMP_REFRESH_FAILED"));
        assertEquals("before", Files.readString(target));
        assertEquals(FileSnapshotStore.STATUS_APPLIED,
                snapshots.findById(snapshotId).orElseThrow().status());
    }

    @Test
    void createToAbsentRewindDoesNotInvokeTimestampRefresh() throws Exception {
        createTask("task-create-no-timestamp");
        Path target = workspace.resolve("generated.txt");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-create-no-timestamp", null, Path.of("generated.txt"));
        Files.writeString(target, "created");
        snapshotService.markAppliedAfterWrite(snapshotId, Path.of("generated.txt"));
        AtomicInteger refreshes = new AtomicInteger();
        RewindService service = new RewindService(workspace, snapshots,
                Clock.systemUTC(), (file, requested) -> {
                    refreshes.incrementAndGet();
                    return requested;
                });

        service.rewind(snapshotId);

        assertFalse(Files.exists(target));
        assertEquals(0, refreshes.get());
    }

    @Test
    void rejectsHashConflictWithoutChangingTarget() throws Exception {
        createTask("task-conflict");
        Path target = workspace.resolve("conflict.txt");
        Files.writeString(target, "original");
        String snapshotId = snapshotService.captureBeforeWrite(
                "task-conflict", null, Path.of("conflict.txt"));
        Files.writeString(target, "replacement");
        snapshotService.markAppliedAfterWrite(snapshotId, Path.of("conflict.txt"));
        Files.writeString(target, "user changed this later");

        RewindService.RewindException error = assertThrows(
                RewindService.RewindException.class,
                () -> rewindService.rewind(snapshotId)
        );

        assertTrue(error.getMessage().contains("sha256_after"));
        assertEquals("user changed this later", Files.readString(target));
        assertEquals(FileSnapshotStore.STATUS_APPLIED,
                snapshots.findById(snapshotId).orElseThrow().status());
    }

    @Test
    void rejectsCapturedFailedAndRestoredStates() throws Exception {
        createTask("task-states");

        String captured = snapshotService.captureBeforeWrite(
                "task-states", null, Path.of("captured.txt"));
        assertStateRejected(captured, FileSnapshotStore.STATUS_CAPTURED);

        String failed = snapshotService.captureBeforeWrite(
                "task-states", null, Path.of("failed.txt"));
        snapshotService.markFailed(failed, "write failed");
        assertStateRejected(failed, FileSnapshotStore.STATUS_FAILED);

        Path restoredTarget = workspace.resolve("restored.txt");
        String restored = snapshotService.captureBeforeWrite(
                "task-states", null, Path.of("restored.txt"));
        Files.writeString(restoredTarget, "content");
        snapshotService.markAppliedAfterWrite(restored, Path.of("restored.txt"));
        rewindService.rewind(restored);
        assertStateRejected(restored, FileSnapshotStore.STATUS_RESTORED);
    }

    @Test
    void rejectsSnapshotPayloadOutsideRootOrMissing() throws Exception {
        createTask("task-payload");
        Path target = workspace.resolve("target.txt");
        Files.writeString(target, "replacement");
        String currentHash = FileSnapshotService.sha256(target);

        snapshots.recordCaptured(
                "snapshot-outside", "task-payload", null, "target.txt",
                Path.of("..", "outside.bin").toString(),
                FileSnapshotStore.OPERATION_OVERWRITE, true, 8, "before-hash"
        );
        snapshots.markApplied("snapshot-outside", currentHash);
        assertThrows(FileSnapshotService.SnapshotException.class,
                () -> rewindService.rewind("snapshot-outside"));
        assertEquals("replacement", Files.readString(target));

        snapshots.recordCaptured(
                "snapshot-missing", "task-payload", null, "target.txt",
                Path.of("task-payload", "snapshot-missing", "original.bin").toString(),
                FileSnapshotStore.OPERATION_OVERWRITE, true, 8, "before-hash"
        );
        snapshots.markApplied("snapshot-missing", currentHash);
        assertThrows(FileSnapshotService.SnapshotException.class,
                () -> rewindService.rewind("snapshot-missing"));
        assertEquals("replacement", Files.readString(target));
    }

    @Test
    void rejectsSymlinkTargetWhenSupported() throws Exception {
        createTask("task-link");
        Path real = workspace.resolve("real.txt");
        Path link = workspace.resolve("link.txt");
        Files.writeString(real, "content");
        createSymlinkOrSkip(link, real.getFileName());

        snapshots.recordCaptured(
                "snapshot-link", "task-link", null, "link.txt", null,
                FileSnapshotStore.OPERATION_CREATE, false, 0, null
        );
        snapshots.markApplied("snapshot-link", "not-used");

        assertThrows(FileSnapshotService.SnapshotException.class,
                () -> rewindService.rewind("snapshot-link"));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("content", Files.readString(real));
    }

    private void assertStateRejected(String snapshotId, String status) {
        RewindService.RewindException error = assertThrows(
                RewindService.RewindException.class,
                () -> rewindService.rewind(snapshotId)
        );
        assertTrue(error.getMessage().contains(status));
    }

    private void createTask(String taskId) throws SQLException {
        tasks.createTask(taskId, "rewind service test", "mock", null, "on-request", 10);
    }

    private void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Symbolic links are unavailable on this platform: " + e.getMessage());
        }
    }
}
