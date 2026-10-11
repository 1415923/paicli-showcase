package com.paicli.interactive.task;

import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.InteractiveRuntimeTaskExecutor;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveTaskManagementIT {
    @TempDir Path temp;

    @Test void legacyResumeNoLongerExecutesTaskRecovery() throws Exception {
        Path workspace = temp.resolve("resume");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-resume", "finish repair", "ollama", "coder",
                    "on-request", 3);
            state.tasks.markFailed("task-resume", "interrupted");
        }
        AtomicInteger initial = new AtomicInteger();
        AtomicInteger resumed = new AtomicInteger();
        InteractiveRuntimeTaskExecutor executor = new InteractiveRuntimeTaskExecutor() {
            public CodingTaskResult execute(String taskId, InteractiveTaskRequest request,
                    StateStore state, com.paicli.interactive.runtime.RuntimeEventBridge bridge) {
                initial.incrementAndGet();
                throw new AssertionError("initial execution must not repeat");
            }
            public CodingTaskResult resume(String taskId, StateStore state,
                    com.paicli.interactive.runtime.RuntimeEventBridge bridge) throws Exception {
                resumed.incrementAndGet();
                state.tasks.prepareForResume(taskId);
                state.tasks.markDone(taskId);
                CodingTaskResult result = new CodingTaskResult(taskId, "resume-run", null,
                        TaskStore.STATUS_DONE, "PASSED", 0, "resumed");
                bridge.taskCompleted(result);
                return result;
            }
        };
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        try (InteractiveRuntimeAdapter runtime = new InteractiveRuntimeAdapter(
                workspace, executor, event -> { }, view, new StateStore(workspace))) {
            InteractiveSession session = session(workspace);
            runtime.openSession(session);
            StateStoreInteractiveQueryFacade queries =
                    new StateStoreInteractiveQueryFacade(workspace, view);
            InteractiveTaskCommandService commands = new InteractiveTaskCommandService(
                    workspace, queries, runtime, prompt -> null);
            assertEquals("LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION",
                    commands.resume(List.of("task-resume"), session).code());
            assertEquals(0, initial.get());
            assertEquals(0, resumed.get());
            assertNull(session.snapshot().lastTaskId());
        }
    }

    @Test void confirmedRewindUsesProductionServiceAndHashGuard() throws Exception {
        Path workspace = temp.resolve("rewind");
        Files.createDirectories(workspace);
        String snapshotId;
        Path target = workspace.resolve("src/Main.java");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "before\n");
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-rewind", "edit main", "ollama", "coder",
                    "on-request", 3);
            state.codingRuns.create("run-rewind", "task-rewind",
                    CodingRunStore.KIND_INITIAL, "", 1, 0);
            FileSnapshotService snapshots = new FileSnapshotService(workspace, state.snapshots);
            snapshotId = snapshots.captureBeforeWrite("task-rewind", "call-1",
                    Path.of("src/Main.java"));
            Files.writeString(target, "after\n");
            snapshots.markAppliedAfterWrite(snapshotId, Path.of("src/Main.java"));
            String afterHash = state.snapshots.findById(snapshotId)
                    .orElseThrow().sha256After();
            state.interactiveProposals.recordProposed("proposal-rewind", "task-rewind",
                    "run-rewind", "src/Main.java", afterHash, 1, 1, false, false);
            state.interactiveProposals.markLatestApplied("task-rewind", "run-rewind",
                    "src/Main.java", snapshotId);
        }
        InteractiveSession session = session(workspace);
        session.startTask("task-rewind"); session.finishTask("task-rewind");
        ArrayDeque<String> answers = new ArrayDeque<>(List.of("y"));
        InteractiveTaskCommandService commands = new InteractiveTaskCommandService(
                workspace, new StateStoreInteractiveQueryFacade(
                        workspace, new InteractiveRuntimeView()),
                request -> TaskSubmissionResult.rejected("UNUSED", "unused"),
                prompt -> answers.isEmpty() ? null : answers.removeFirst());
        assertEquals("REWIND_COMPLETED",
                commands.rewind(List.of(snapshotId), session).code());
        assertEquals("before\n", Files.readString(target));
        try (StateStore state = new StateStore(workspace)) {
            assertEquals("RESTORED", state.snapshots.findById(snapshotId).orElseThrow().status());
        }
    }

    private static InteractiveSession session(Path workspace) {
        InteractiveSession session = new InteractiveSession("session-1", workspace,
                "ollama", "coder", "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.EPOCH);
        session.start(); return session;
    }
}
