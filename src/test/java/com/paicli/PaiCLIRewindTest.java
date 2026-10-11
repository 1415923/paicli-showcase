package com.paicli;

import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaiCLIRewindTest {

    @TempDir
    Path workspace;

    @Test
    void rewindCreateDeletesFileWithoutRunningAgent() throws Exception {
        String taskId = "cli-rewind-create";
        Path parent = workspace.resolve("generated");
        Files.createDirectories(parent);
        Path target = parent.resolve("new.txt");
        String snapshotId = seedCreate(taskId, Path.of("generated/new.txt"), "generated");
        Files.writeString(workspace.resolve(".paicli/config.json"), "{not-valid-json");

        CliOutput output = runCli("rewind", snapshotId);

        assertTrue(output.stderr().isBlank());
        assertTrue(output.stdout().contains("Snapshot ID: " + snapshotId));
        assertTrue(output.stdout().contains("File path  : generated\\new.txt")
                || output.stdout().contains("File path  : generated/new.txt"));
        assertTrue(output.stdout().contains("Operation  : CREATE"));
        assertTrue(output.stdout().contains("Result     : deleted file"));
        assertFalse(output.stdout().contains("Active provider"));
        assertFalse(Files.exists(target));
        assertTrue(Files.isDirectory(parent));
        assertFalse(Files.exists(workspace.resolve(".paicli/sessions")));
        assertFalse(Files.exists(workspace.resolve("reports/runs")));
        try (StateStore stateStore = new StateStore(workspace)) {
            assertEquals(1, stateStore.tasks.findAll(10).size());
            assertTrue(stateStore.codingRuns.findByTask(taskId).isEmpty());
            assertEquals(FileSnapshotStore.STATUS_RESTORED,
                    stateStore.snapshots.findById(snapshotId).orElseThrow().status());
        }
    }

    @Test
    void rewindOverwriteRestoresOriginalContent() throws Exception {
        String taskId = "cli-rewind-overwrite";
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "original");
        String snapshotId = seedOverwrite(taskId, Path.of("existing.txt"), "replacement");

        CliOutput output = runCli("rewind", snapshotId);

        assertTrue(output.stderr().isBlank());
        assertTrue(output.stdout().contains("Operation  : OVERWRITE"));
        assertTrue(output.stdout().contains("Result     : restored file"));
        assertEquals("original", Files.readString(target));
    }

    @Test
    void rewindHashConflictFailsWithoutChangingTarget() throws Exception {
        String taskId = "cli-rewind-conflict";
        Path target = workspace.resolve("conflict.txt");
        Files.writeString(target, "original");
        String snapshotId = seedOverwrite(taskId, Path.of("conflict.txt"), "replacement");
        Files.writeString(target, "later user change");

        CliOutput output = runCli("rewind", snapshotId);

        assertTrue(output.stderr().contains("Current file hash does not match sha256_after"));
        assertTrue(output.stdout().isBlank());
        assertEquals("later user change", Files.readString(target));
        try (StateStore stateStore = new StateStore(workspace)) {
            assertEquals(FileSnapshotStore.STATUS_APPLIED,
                    stateStore.snapshots.findById(snapshotId).orElseThrow().status());
        }
    }

    @Test
    void missingSnapshotPrintsClearErrorAndDoesNotCreateTask() throws Exception {
        CliOutput output = runCli("rewind", "missing-snapshot");

        assertTrue(output.stderr().contains("Snapshot not found: missing-snapshot"));
        assertFalse(output.stderr().contains("NullPointerException"));
        assertFalse(output.stdout().contains("Active provider"));
        assertFalse(Files.exists(workspace.resolve(".paicli/sessions")));
        assertFalse(Files.exists(workspace.resolve("reports/runs")));
        try (StateStore stateStore = new StateStore(workspace)) {
            assertTrue(stateStore.tasks.findAll(10).isEmpty());
        }
    }

    @Test
    void rewindWithoutSnapshotIdPrintsUsageErrorWithoutOpeningStateStore() throws Exception {
        CliOutput output = runCli("rewind");

        assertTrue(output.stderr().contains("rewind requires exactly one snapshotId"));
        assertTrue(output.stdout().contains("paicli rewind <snapshotId>"));
        assertFalse(Files.exists(workspace.resolve(".paicli/state/paicli.db")));
    }

    @Test
    void restoredSnapshotCannotBeRewoundAgain() throws Exception {
        String taskId = "cli-rewind-repeat";
        String snapshotId = seedCreate(taskId, Path.of("repeat.txt"), "content");
        CliOutput first = runCli("rewind", snapshotId);
        assertTrue(first.stderr().isBlank());

        CliOutput second = runCli("rewind", snapshotId);

        assertTrue(second.stderr().contains("Snapshot is not APPLIED: RESTORED"));
        assertTrue(second.stdout().isBlank());
    }

    private String seedCreate(String taskId, Path relativeTarget, String content) throws Exception {
        try (StateStore stateStore = new StateStore(workspace)) {
            createTask(stateStore, taskId);
            FileSnapshotService service = new FileSnapshotService(workspace, stateStore.snapshots);
            String snapshotId = service.captureBeforeWrite(taskId, null, relativeTarget);
            Path target = workspace.resolve(relativeTarget);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, content);
            service.markAppliedAfterWrite(snapshotId, relativeTarget);
            return snapshotId;
        }
    }

    private String seedOverwrite(String taskId, Path relativeTarget, String replacement) throws Exception {
        try (StateStore stateStore = new StateStore(workspace)) {
            createTask(stateStore, taskId);
            FileSnapshotService service = new FileSnapshotService(workspace, stateStore.snapshots);
            String snapshotId = service.captureBeforeWrite(taskId, null, relativeTarget);
            Files.writeString(workspace.resolve(relativeTarget), replacement);
            service.markAppliedAfterWrite(snapshotId, relativeTarget);
            return snapshotId;
        }
    }

    private void createTask(StateStore stateStore, String taskId) throws Exception {
        stateStore.tasks.createTask(taskId, "rewind CLI test", "mock", null, "on-request", 10);
    }

    private CliOutput runCli(String... args) throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            System.setOut(out);
            System.setErr(err);
            PaiCLI.run(args);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
        return new CliOutput(
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
        );
    }

    private record CliOutput(String stdout, String stderr) {
    }
}
