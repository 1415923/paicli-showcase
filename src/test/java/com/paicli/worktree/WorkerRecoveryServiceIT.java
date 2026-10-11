package com.paicli.worktree;

import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import com.paicli.worker.WorkerRecoveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRecoveryServiceIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private StateStore state;
    private ManagedWorktreeService worktrees;

    @BeforeEach
    void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        managedRoot = tempDir.resolve("managed");
        state = new StateStore(tempDir.resolve("coordinator"));
        worktrees = new ManagedWorktreeService(state.managedWorktrees);
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (state != null) state.close();
    }

    @Test
    void interruptsRunAndClaimAndRetainsDirtyWorkspaceAsAbandoned() throws Exception {
        var created = worktrees.createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "recovery-dirty"));
        createRunningClaim(created.record().managedWorktreeId());
        Files.writeString(created.workspace().resolve("valuable.txt"), "retain me\n");

        var result = recovery().reconcileInterruptedWorker("run-1", "claim-1");

        assertEquals("DIRTY_INTERRUPTED_WORKSPACE", result.finding());
        assertTrue(result.residueRetained());
        assertEquals("INTERRUPTED", state.workerRuns.findById("run-1").orElseThrow().status());
        assertEquals("INTERRUPTED", state.taskClaims.findById("claim-1").orElseThrow().status());
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED,
                state.managedWorktrees.findById("recovery-dirty").orElseThrow().status());
        assertTrue(Files.exists(created.workspace().resolve("valuable.txt")));
    }

    @Test
    void missingRealWorktreeInterruptsMetadataAndMarksItLost() throws Exception {
        var created = worktrees.createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "recovery-missing"));
        createRunningClaim(created.record().managedWorktreeId());
        GitTestSupport.require(GitTestSupport.git(repository, "worktree", "remove",
                created.workspace().toString()));

        var result = recovery().reconcileInterruptedWorker("run-1", "claim-1");

        assertEquals("WORKTREE_MISSING", result.finding());
        assertEquals("INTERRUPTED", state.workerRuns.findById("run-1").orElseThrow().status());
        assertEquals("INTERRUPTED", state.taskClaims.findById("claim-1").orElseThrow().status());
        assertEquals(ManagedWorktreeStore.STATUS_LOST,
                state.managedWorktrees.findById("recovery-missing").orElseThrow().status());
    }

    @Test
    void cleanInterruptedWorkspaceIsNotAutomaticallyRetriedOrReassigned() throws Exception {
        var created = worktrees.createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "recovery-clean"));
        createRunningClaim(created.record().managedWorktreeId());

        var result = recovery().reconcileInterruptedWorker("run-1", "claim-1");

        assertEquals("CLEAN_INTERRUPTED_WORKSPACE", result.finding());
        assertTrue(result.cleanWorkspace());
        assertTrue(result.userActionRequired());
        assertFalse(result.newAttemptAllowed());
        assertEquals("INTERRUPTED", state.workerRuns.findById("run-1").orElseThrow().status());
        assertEquals("INTERRUPTED", state.taskClaims.findById("claim-1").orElseThrow().status());
        assertEquals(ManagedWorktreeStore.STATUS_ACTIVE,
                state.managedWorktrees.findById("recovery-clean").orElseThrow().status());
    }

    private void createRunningClaim(String worktreeId) throws Exception {
        state.workerTasks.create("task-item-1", null, "bounded task", "a".repeat(64));
        state.workerRuns.create("run-1", null, "task-item-1", worktreeId,
                "occurrence-1", 1, "mock", "b".repeat(64));
        state.managedWorktrees.assignWorker(worktreeId, "run-1");
        state.workerRuns.markPreparing("run-1");
        state.taskClaims.acquireTaskClaim("claim-1", "task-item-1", "run-1",
                "occurrence-1", 1);
        state.workerRuns.markRunning("run-1");
    }

    private WorkerRecoveryService recovery() {
        return new WorkerRecoveryService(state, worktrees, repository);
    }
}
