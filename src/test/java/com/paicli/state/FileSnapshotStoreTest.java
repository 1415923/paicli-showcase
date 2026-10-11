package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileSnapshotStoreTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private TaskStore tasks;
    private FileSnapshotStore snapshots;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"));
        Connection connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        snapshots = new FileSnapshotStore(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void recordsCapturedSnapshotAndFindsItByIdAndTask() throws Exception {
        createTask("task-001");

        snapshots.recordCaptured(
                "snapshot-001", "task-001", "call-001", "src/Main.java",
                "task-001/snapshot-001/original.bin", FileSnapshotStore.OPERATION_OVERWRITE,
                true, 42, "before-hash"
        );

        FileSnapshotStore.FileSnapshotRecord record = snapshots.findById("snapshot-001").orElseThrow();
        assertEquals("task-001", record.taskId());
        assertEquals("call-001", record.toolCallId());
        assertEquals("src/Main.java", record.filePath());
        assertEquals("task-001/snapshot-001/original.bin", record.snapshotPath());
        assertEquals(FileSnapshotStore.OPERATION_OVERWRITE, record.operation());
        assertTrue(record.originalExists());
        assertEquals(42, record.originalSize());
        assertEquals("before-hash", record.sha256Before());
        assertNull(record.sha256After());
        assertEquals(FileSnapshotStore.STATUS_CAPTURED, record.status());
        assertNotNull(record.createdAt());
        assertNull(record.restoredAt());
        assertEquals(List.of("snapshot-001"), snapshots.findByTask("task-001").stream()
                .map(FileSnapshotStore.FileSnapshotRecord::snapshotId).toList());
        assertEquals(1, snapshots.findByStatus(FileSnapshotStore.STATUS_CAPTURED).size());
    }

    @Test
    void marksSnapshotsAppliedFailedAndRestored() throws Exception {
        createTask("task-002");
        recordCreateSnapshot("snapshot-applied", "task-002");
        recordCreateSnapshot("snapshot-failed", "task-002");

        snapshots.markApplied("snapshot-applied", "after-hash");
        FileSnapshotStore.FileSnapshotRecord applied = snapshots.findById("snapshot-applied").orElseThrow();
        assertEquals(FileSnapshotStore.STATUS_APPLIED, applied.status());
        assertEquals("after-hash", applied.sha256After());
        assertEquals(1, snapshots.findByStatus(FileSnapshotStore.STATUS_APPLIED).size());

        snapshots.markFailed("snapshot-failed", "write failed");
        assertEquals(FileSnapshotStore.STATUS_FAILED,
                snapshots.findById("snapshot-failed").orElseThrow().status());
        assertEquals(1, snapshots.findByStatus(FileSnapshotStore.STATUS_FAILED).size());

        snapshots.markRestored("snapshot-applied");
        FileSnapshotStore.FileSnapshotRecord restored = snapshots.findById("snapshot-applied").orElseThrow();
        assertEquals(FileSnapshotStore.STATUS_RESTORED, restored.status());
        assertNotNull(restored.restoredAt());
        assertTrue(snapshots.findByStatus(FileSnapshotStore.STATUS_APPLIED).isEmpty());
        assertEquals(1, snapshots.findByStatus(FileSnapshotStore.STATUS_RESTORED).size());
    }

    @Test
    void rejectsSnapshotForMissingTask() {
        assertThrows(SQLException.class, () -> snapshots.recordCaptured(
                "snapshot-missing", "missing-task", null, "new.txt", null,
                FileSnapshotStore.OPERATION_CREATE, false, 0, null
        ));
    }

    @Test
    void stateStoreInitializesSnapshotFacadeAndClosesConnection() throws Exception {
        Path workspace = tempDir.resolve("facade");
        StateStore stateStore = new StateStore(workspace);
        try (stateStore) {
            assertNotNull(stateStore.snapshots);
            assertTrue(Files.exists(workspace.resolve(".paicli/state/paicli.db")));
            stateStore.tasks.createTask("task-003", "snapshot", "mock", null, "on-request", 10);
            stateStore.snapshots.recordCaptured(
                    "snapshot-003", "task-003", null, "new.txt", null,
                    FileSnapshotStore.OPERATION_CREATE, false, 0, null
            );
            assertTrue(stateStore.snapshots.findById("snapshot-003").isPresent());
        }
        assertThrows(SQLException.class, () -> stateStore.snapshots.findByTask("task-003"));
    }

    @Test
    void rejectsInvalidStateTransitions() throws Exception {
        createTask("task-004");
        recordCreateSnapshot("snapshot-004", "task-004");

        assertThrows(SQLException.class, () -> snapshots.markRestored("snapshot-004"));
        snapshots.markFailed("snapshot-004", "failed");
        assertThrows(SQLException.class, () -> snapshots.markApplied("snapshot-004", "after"));
    }

    private void createTask(String taskId) throws SQLException {
        tasks.createTask(taskId, "snapshot test", "mock", null, "on-request", 10);
    }

    private void recordCreateSnapshot(String snapshotId, String taskId) throws SQLException {
        snapshots.recordCaptured(
                snapshotId, taskId, null, "new.txt", null,
                FileSnapshotStore.OPERATION_CREATE, false, 0, null
        );
    }
}
