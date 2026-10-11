package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class FileSnapshotServiceTest {

    @TempDir
    Path tempDir;

    private Path workspace;
    private DatabaseManager databaseManager;
    private TaskStore tasks;
    private FileSnapshotStore snapshots;
    private FileSnapshotService service;

    @BeforeEach
    void setUp() throws Exception {
        workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        databaseManager = new DatabaseManager(workspace.resolve(".paicli/state"));
        Connection connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        snapshots = new FileSnapshotStore(connection);
        service = new FileSnapshotService(workspace, snapshots);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void capturesNewFileAsCreateWithoutPayload() throws Exception {
        createTask("task-create");

        String snapshotId = service.captureBeforeWrite("task-create", "call-1", Path.of("new.txt"));

        FileSnapshotStore.FileSnapshotRecord record = snapshots.findById(snapshotId).orElseThrow();
        assertEquals(FileSnapshotStore.OPERATION_CREATE, record.operation());
        assertFalse(record.originalExists());
        assertEquals(0, record.originalSize());
        assertNull(record.sha256Before());
        assertNull(record.snapshotPath());
        assertEquals(FileSnapshotStore.STATUS_CAPTURED, record.status());
    }

    @Test
    void capturesExistingFileBytesAndSha256() throws Exception {
        createTask("task-overwrite");
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "original content");

        String snapshotId = service.captureBeforeWrite(
                "task-overwrite", "call-2", Path.of("existing.txt"));

        FileSnapshotStore.FileSnapshotRecord record = snapshots.findById(snapshotId).orElseThrow();
        assertEquals(FileSnapshotStore.OPERATION_OVERWRITE, record.operation());
        assertTrue(record.originalExists());
        assertEquals(Files.size(target), record.originalSize());
        assertEquals(FileSnapshotService.sha256(target), record.sha256Before());
        assertNotNull(record.snapshotPath());
        Path payload = workspace.resolve(".paicli/snapshots").resolve(record.snapshotPath());
        assertEquals("original content", Files.readString(payload));
    }

    @Test
    void persistsNestedPathsWithPortableSeparators() throws Exception {
        createTask("task-portable-path");
        Path target = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "class Sample {}\n");

        String snapshotId = service.captureBeforeWrite(
                "task-portable-path", "call-portable", workspace.relativize(target));

        FileSnapshotStore.FileSnapshotRecord record = snapshots.findById(snapshotId).orElseThrow();
        assertEquals("src/main/java/demo/Sample.java", record.filePath());
        assertNotNull(record.snapshotPath());
        assertFalse(record.snapshotPath().contains("\\"));
    }

    @Test
    void marksAppliedWithCurrentTargetHash() throws Exception {
        createTask("task-applied");
        Path target = workspace.resolve("applied.txt");
        String snapshotId = service.captureBeforeWrite("task-applied", null, Path.of("applied.txt"));
        Files.writeString(target, "new content");

        service.markAppliedAfterWrite(snapshotId, Path.of("applied.txt"));

        FileSnapshotStore.FileSnapshotRecord record = snapshots.findById(snapshotId).orElseThrow();
        assertEquals(FileSnapshotStore.STATUS_APPLIED, record.status());
        assertEquals(FileSnapshotService.sha256(target), record.sha256After());
    }

    @Test
    void rejectsSensitiveFileNamesWithoutContentScanning() throws Exception {
        createTask("task-sensitive");
        String[] sensitivePaths = {
                ".env", ".env.local", "certificate.pem", "private.key",
                "id_rsa", "credentials.json", "secrets.yaml"
        };

        for (String sensitivePath : sensitivePaths) {
            FileSnapshotService.SnapshotException error = assertThrows(
                    FileSnapshotService.SnapshotException.class,
                    () -> service.captureBeforeWrite("task-sensitive", null, Path.of(sensitivePath))
            );
            assertTrue(error.getMessage().contains("sensitive file path"));
        }
        assertTrue(snapshots.findByTask("task-sensitive").isEmpty());
    }

    @Test
    void rejectsTargetOutsideWorkspace() throws Exception {
        createTask("task-outside");
        Path outside = tempDir.resolve("outside.txt");

        assertThrows(FileSnapshotService.SnapshotException.class,
                () -> service.captureBeforeWrite("task-outside", null, outside));
        assertTrue(snapshots.findByTask("task-outside").isEmpty());
    }

    @Test
    void rejectsExistingFileAboveSnapshotLimit() throws Exception {
        createTask("task-large");
        Path target = workspace.resolve("large.txt");
        Files.write(target, new byte[(int) FileSnapshotService.DEFAULT_MAX_SNAPSHOT_BYTES + 1]);

        FileSnapshotService.SnapshotException error = assertThrows(
                FileSnapshotService.SnapshotException.class,
                () -> service.captureBeforeWrite("task-large", null, Path.of("large.txt"))
        );

        assertTrue(error.getMessage().contains("snapshot target too large"));
        assertTrue(snapshots.findByTask("task-large").isEmpty());
    }

    @Test
    void failedCaptureRemovesPayloadAndLeavesNoCapturedMetadata() throws Exception {
        Path target = workspace.resolve("orphan.txt");
        Files.writeString(target, "original");

        assertThrows(SQLException.class,
                () -> service.captureBeforeWrite("missing-task", null, Path.of("orphan.txt")));

        assertTrue(snapshots.findByTask("missing-task").isEmpty());
        Path snapshotRoot = workspace.resolve(".paicli/snapshots");
        try (var paths = Files.walk(snapshotRoot)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().equals("original.bin")));
        }
    }

    @Test
    void rejectsSymlinkCaptureWhenSupported() throws Exception {
        createTask("task-link");
        Path real = workspace.resolve("real.txt");
        Path link = workspace.resolve("link.txt");
        Files.writeString(real, "content");
        createSymlinkOrSkip(link, real.getFileName());

        assertThrows(FileSnapshotService.SnapshotException.class,
                () -> service.captureBeforeWrite("task-link", null, Path.of("link.txt")));
        assertTrue(snapshots.findByTask("task-link").isEmpty());
    }

    private void createTask(String taskId) throws SQLException {
        tasks.createTask(taskId, "snapshot service test", "mock", null, "on-request", 10);
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
