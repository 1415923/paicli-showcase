package com.paicli.interactive.runtime;

import com.paicli.runtime.CodingTaskResult;
import com.paicli.safety.ApprovalDecision;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveTaskLifecycleTest {
    @TempDir Path temp;

    @Test void appliedWriteWithoutStructuredVerificationIsUnverified() throws Exception {
        try (StateStore state = new StateStore(temp)) {
            createDone(state, "task-u");
            state.snapshots.recordCaptured("snapshot-u", "task-u", null,
                    "src/A.java", null, FileSnapshotStore.OPERATION_CREATE,
                    false, 0, null);
            state.snapshots.markApplied("snapshot-u", "a".repeat(64));
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(
                    result("task-u", VerificationStore.STATUS_SKIPPED), state);
            assertEquals(TaskStore.STATUS_COMPLETED_UNVERIFIED,
                    outcome.persistedStatus());
            assertEquals("COMPLETED_UNVERIFIED", outcome.code());
        }
    }

    @Test void rejectedRequiredWriteIsIncompleteNotCompleted() throws Exception {
        try (StateStore state = new StateStore(temp)) {
            createDone(state, "task-r");
            state.approvals.record("task-r", 1, null, "write_file", "HIGH",
                    ApprovalDecision.Decision.DENY.name(),
                    "interactive user rejected request");
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(
                    result("task-r", VerificationStore.STATUS_SKIPPED), state);
            assertEquals(TaskStore.STATUS_REJECTED, outcome.persistedStatus());
            assertEquals("REJECTED", outcome.code());
        }
    }

    @Test void passedStructuredVerificationIsVerified() throws Exception {
        try (StateStore state = new StateStore(temp)) {
            createDone(state, "task-v");
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(
                    result("task-v", VerificationStore.STATUS_PASSED), state);
            assertEquals(TaskStore.STATUS_COMPLETED_VERIFIED,
                    outcome.persistedStatus());
        }
    }

    @Test void successfulPlanStepsAwaitCompletionGateWithoutBeingReportedFailed()
            throws Exception {
        try(StateStore state=new StateStore(temp)){
            CodingTaskResult pending=new CodingTaskResult("task-p","run-p","pln-p",
                    TaskStore.STATUS_RUNNING,null,0,
                    "Plan Steps executed; completion evidence is pending.");
            InteractiveTaskOutcome outcome=InteractiveTaskOutcome.classify(pending,state);
            assertEquals(TaskStore.STATUS_RUNNING,outcome.persistedStatus());
            assertEquals(com.paicli.interactive.task.TaskSubmissionResult.Status.INCOMPLETE,
                    outcome.resultStatus());
            assertEquals("INCOMPLETE",outcome.code());
        }
    }

    private static void createDone(StateStore state, String taskId) throws Exception {
        state.tasks.createTask(taskId, "goal", "mock", "model", "on-request", 5);
        state.tasks.markRunning(taskId);
        state.tasks.markDone(taskId);
    }
    private static CodingTaskResult result(String taskId, String verification) {
        return new CodingTaskResult(taskId, "run-1", null, TaskStore.STATUS_DONE,
                verification, 0, "done");
    }
}
