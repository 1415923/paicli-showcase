package com.paicli.worker;

import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskClaimStore;
import com.paicli.state.WorkerRunStore;
import com.paicli.worktree.ManagedWorktreeException;
import com.paicli.worktree.ManagedWorktreeService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Objects;

/** Non-destructive reconciliation for interrupted Worker execution metadata. */
public final class WorkerRecoveryService {
    private final StateStore state;
    private final ManagedWorktreeService worktrees;
    private final Path repositoryRoot;

    public WorkerRecoveryService(StateStore state, ManagedWorktreeService worktrees,
                                 Path repositoryRoot) {
        this.state = Objects.requireNonNull(state, "state is required");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees is required");
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot is required")
                .toAbsolutePath().normalize();
    }

    public RecoveryResult reconcileInterruptedWorker(String workerRunId,
                                                     String taskClaimId) {
        try {
            WorkerRunStore.WorkerRunRecord run = state.workerRuns.findById(workerRunId)
                    .orElseThrow(() -> new IllegalArgumentException("Worker run not found"));
            TaskClaimStore.TaskClaimRecord claim = taskClaimId == null ? null
                    : state.taskClaims.findById(taskClaimId).orElseThrow(
                            () -> new IllegalArgumentException("Task claim not found"));
            if (claim != null && !claim.workerRunId().equals(workerRunId)) {
                throw new IllegalArgumentException("Task claim does not belong to Worker run");
            }

            ManagedWorktreeService.Inspection inspection =
                    worktrees.inspectManagedWorktree(run.worktreeId(), repositoryRoot);
            if (inspection.finding() == ManagedWorktreeService.Finding.MISSING) {
                interrupt(run, claim, "managed worktree missing during reconciliation");
                markLost(inspection.record(), "managed worktree missing during reconciliation");
                return new RecoveryResult("LOST", false, false, true, true,
                        "WORKTREE_MISSING");
            }
            if (inspection.finding() != ManagedWorktreeService.Finding.MATCHED) {
                interrupt(run, claim, "managed worktree mismatch during reconciliation");
                markAbandoned(inspection.record(), "managed worktree mismatch retained");
                return new RecoveryResult("ABANDONED", false, false, true, true,
                        "WORKTREE_MISMATCH");
            }

            boolean dirty = worktreeDirty(inspection.record());
            interrupt(run, claim, "interrupted Worker process reconciled");
            if (dirty) {
                state.managedWorktrees.markAbandonedForWorker(run.worktreeId(), workerRunId,
                        "dirty interrupted workspace retained", true);
                return new RecoveryResult("ABANDONED", false, false, true, true,
                        "DIRTY_INTERRUPTED_WORKSPACE");
            }
            return new RecoveryResult(inspection.record().status(), true, false, true, true,
                    "CLEAN_INTERRUPTED_WORKSPACE");
        } catch (SQLException | ManagedWorktreeException e) {
            throw new IllegalStateException("Worker reconciliation failed closed", e);
        }
    }

    private void interrupt(WorkerRunStore.WorkerRunRecord run,
                           TaskClaimStore.TaskClaimRecord claim, String reason)
            throws SQLException {
        if ("RUNNING".equals(run.status()) || "PREPARING".equals(run.status())) {
            state.workerRuns.interrupt(run.workerRunId(), reason);
        }
        if (claim != null && "ACTIVE".equals(claim.status())) {
            state.taskClaims.interrupt(claim.taskClaimId(), reason);
        }
    }

    private boolean worktreeDirty(ManagedWorktreeStore.ManagedWorktreeRecord record) {
        Path workspace = Path.of(record.canonicalWorkspacePath());
        if (!Files.isDirectory(workspace)) return true;
        try {
            return worktrees.isWorktreeDirty(record.managedWorktreeId(), repositoryRoot);
        } catch (ManagedWorktreeException notActive) {
            return !"CLEAN".equals(record.dirtyState());
        }
    }

    private void markLost(ManagedWorktreeStore.ManagedWorktreeRecord record, String reason)
            throws SQLException {
        if (!ManagedWorktreeStore.STATUS_LOST.equals(record.status())) {
            state.managedWorktrees.markLost(record.managedWorktreeId(), reason);
        }
    }

    private void markAbandoned(ManagedWorktreeStore.ManagedWorktreeRecord record, String reason)
            throws SQLException {
        if (!ManagedWorktreeStore.STATUS_ABANDONED.equals(record.status())) {
            state.managedWorktrees.markAbandoned(record.managedWorktreeId(), reason);
        }
    }

    public record RecoveryResult(String worktreeStatus, boolean cleanWorkspace,
            boolean newAttemptAllowed, boolean residueRetained,
            boolean userActionRequired, String finding) { }
}
