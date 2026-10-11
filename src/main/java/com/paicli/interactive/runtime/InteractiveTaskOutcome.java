package com.paicli.interactive.runtime;

import com.paicli.runtime.CodingTaskResult;
import com.paicli.safety.ApprovalDecision;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;

import java.sql.SQLException;

/** Classifies interactive terminal state from durable side-effect facts. */
record InteractiveTaskOutcome(String persistedStatus, String code,
        com.paicli.interactive.task.TaskSubmissionResult.Status resultStatus,
        String message) {

    static InteractiveTaskOutcome classify(CodingTaskResult result, StateStore state)
            throws SQLException {
        if (TaskStore.STATUS_RUNNING.equals(result.finalStatus())) {
            return new InteractiveTaskOutcome(TaskStore.STATUS_RUNNING,
                    "INCOMPLETE", com.paicli.interactive.task.TaskSubmissionResult.Status.INCOMPLETE,
                    result.summary());
        }
        if (!TaskStore.STATUS_DONE.equals(result.finalStatus())) {
            return new InteractiveTaskOutcome(TaskStore.STATUS_FAILED,
                    "FAILED", com.paicli.interactive.task.TaskSubmissionResult.Status.FAILED,
                    result.summary());
        }
        long appliedWrites = state.snapshots.findByTask(result.taskId()).stream()
                .filter(item -> FileSnapshotStore.STATUS_APPLIED.equals(item.status()))
                .count();
        boolean rejectedRequiredAction = state.approvals.findByTask(result.taskId()).stream()
                .anyMatch(item -> ApprovalDecision.Decision.DENY.name().equals(item.decision())
                        && "write_file".equals(item.toolName())
                        && item.reason() != null
                        && (item.reason().contains("interactive user rejected")
                            || item.reason().contains("DUPLICATE_PROPOSAL")
                            || item.reason().contains("REJECT_BUDGET_EXHAUSTED")
                            || item.reason().contains("WRITE_TARGET_")
                            || item.reason().contains("existing files only")));
        if (rejectedRequiredAction && appliedWrites == 0) {
            return new InteractiveTaskOutcome(TaskStore.STATUS_REJECTED,
                    "REJECTED", com.paicli.interactive.task.TaskSubmissionResult.Status.INCOMPLETE,
                    "A required operation was rejected; the task is incomplete.");
        }
        if (VerificationStore.STATUS_PASSED.equals(result.verificationStatus())) {
            return new InteractiveTaskOutcome(TaskStore.STATUS_COMPLETED_VERIFIED,
                    "COMPLETED_VERIFIED",
                    com.paicli.interactive.task.TaskSubmissionResult.Status.COMPLETED_VERIFIED,
                    result.summary());
        }
        if (VerificationStore.STATUS_SKIPPED.equals(result.verificationStatus())
                && appliedWrites > 0) {
            return new InteractiveTaskOutcome(TaskStore.STATUS_COMPLETED_UNVERIFIED,
                    "COMPLETED_UNVERIFIED",
                    com.paicli.interactive.task.TaskSubmissionResult.Status.COMPLETED_UNVERIFIED,
                    "Changes were applied but structured verification was disabled.");
        }
        return new InteractiveTaskOutcome(TaskStore.STATUS_DONE, "COMPLETED",
                com.paicli.interactive.task.TaskSubmissionResult.Status.COMPLETED,
                result.summary());
    }
}
