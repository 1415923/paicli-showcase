package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class CodingRuntimeStoreTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void codingRunTracksLifecycleAndEnforcesOneActiveRun() throws Exception {
        createTask("task-001");

        stateStore.codingRuns.create(
                "run-001", "task-001", CodingRunStore.KIND_INITIAL, null, 1, 0
        );
        CodingRunStore.CodingRunRecord created = stateStore.codingRuns
                .findById("run-001").orElseThrow();
        assertEquals(CodingRunStore.PHASE_PLANNING, created.phase());
        assertNull(created.verificationCommand());
        assertEquals(1, created.maxRepairAttempts());
        assertEquals(0, created.repairAttemptsCompleted());
        assertNull(created.skillsManifestSha256());
        assertNull(created.hooksManifestSha256());
        assertEquals("run-001", stateStore.codingRuns.findActiveByTask("task-001")
                .orElseThrow().runId());

        assertThrows(SQLException.class, () -> stateStore.codingRuns.create(
                "run-concurrent", "task-001", CodingRunStore.KIND_RESUME, "mvn test", 1, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> stateStore.codingRuns.transitionPhase(
                "run-001", CodingRunStore.PHASE_PLANNING, CodingRunStore.PHASE_VERIFYING
        ));

        stateStore.codingRuns.transitionPhase(
                "run-001", CodingRunStore.PHASE_PLANNING, CodingRunStore.PHASE_EXECUTING
        );
        stateStore.codingRuns.updateLastIteration("run-001", 3);
        stateStore.codingRuns.updateRuntimeManifestHashes(
                "run-001", "skills-hash", "hooks-hash");
        CodingRunStore.CodingRunRecord withManifests = stateStore.codingRuns
                .findById("run-001").orElseThrow();
        assertEquals("skills-hash", withManifests.skillsManifestSha256());
        assertEquals("hooks-hash", withManifests.hooksManifestSha256());
        assertEquals(1, stateStore.codingRuns.incrementRepairAttempts("run-001"));
        assertThrows(SQLException.class,
                () -> stateStore.codingRuns.incrementRepairAttempts("run-001"));
        assertThrows(SQLException.class, () -> stateStore.codingRuns.transitionPhase(
                "run-001", CodingRunStore.PHASE_REPAIRING, CodingRunStore.PHASE_VERIFYING
        ));

        stateStore.codingRuns.markCompleted("run-001");
        CodingRunStore.CodingRunRecord completed = stateStore.codingRuns
                .findById("run-001").orElseThrow();
        assertEquals(CodingRunStore.PHASE_COMPLETED, completed.phase());
        assertEquals(3, completed.lastIteration());
        assertNotNull(completed.completedAt());
        assertTrue(stateStore.codingRuns.findActiveByTask("task-001").isEmpty());

        stateStore.codingRuns.create(
                "run-002", "task-001", CodingRunStore.KIND_RESUME, "mvn test", 5, 3
        );
        assertEquals(2, stateStore.codingRuns.findByTask("task-001").size());
        assertThrows(SQLException.class, () -> stateStore.codingRuns.create(
                "run-invalid-0", "task-001", CodingRunStore.KIND_RESUME, null, 0, 3
        ));
        assertThrows(SQLException.class, () -> stateStore.codingRuns.create(
                "run-invalid-6", "task-001", CodingRunStore.KIND_RESUME, null, 6, 3
        ));
    }

    @Test
    void cancelledRunHasEffectiveCancelledPhaseAndLeavesNoActiveRun() throws Exception {
        createTask("task-cancelled-run");
        createRun("run-cancelled", "task-cancelled-run", 1);
        stateStore.codingRuns.transitionPhase("run-cancelled",
                CodingRunStore.PHASE_PLANNING, CodingRunStore.PHASE_EXECUTING);

        stateStore.codingRuns.markCancelled("run-cancelled", false);

        CodingRunStore.CodingRunRecord cancelled = stateStore.codingRuns
                .findById("run-cancelled").orElseThrow();
        assertEquals(CodingRunStore.PHASE_CANCELLED, cancelled.phase());
        assertEquals("CANCELLED: user requested", cancelled.lastError());
        assertTrue(stateStore.codingRuns.findActiveByTask("task-cancelled-run").isEmpty());
    }

    @Test
    void taskPlanPersistsPlanTextAndEnforcesRunOwnership() throws Exception {
        createTask("task-plan");
        createTask("other-task");
        createRun("run-plan", "task-plan", 1);

        stateStore.plans.recordGenerated(
                "plan-001", "run-plan", "task-plan", "1. Inspect\n2. Implement\n3. Verify"
        );
        TaskPlanStore.TaskPlanRecord generated = stateStore.plans
                .findByRun("run-plan").orElseThrow();
        assertEquals(TaskPlanStore.STATUS_GENERATED, generated.status());
        assertTrue(generated.planText().contains("Implement"));

        stateStore.plans.markActive("plan-001");
        stateStore.plans.markCompleted("plan-001");
        assertEquals(TaskPlanStore.STATUS_COMPLETED,
                stateStore.plans.findById("plan-001").orElseThrow().status());
        assertThrows(SQLException.class, () -> stateStore.plans.markActive("plan-001"));
        assertThrows(SQLException.class, () -> stateStore.plans.recordGenerated(
                "plan-duplicate", "run-plan", "task-plan", "duplicate"
        ));
        assertThrows(SQLException.class, () -> stateStore.plans.recordGenerated(
                "plan-blank", "run-plan", "task-plan", "   "
        ));
        assertThrows(SQLException.class, () -> stateStore.plans.recordGenerated(
                "plan-mismatch", "run-plan", "other-task", "wrong owner"
        ));
    }

    @Test
    void verificationStoresOnlyExcerptsAndSupportsSkipped() throws Exception {
        createTask("task-verify");
        createRun("run-verify", "task-verify", 2);

        stateStore.verifications.recordSkipped(
                "verification-skipped", "run-verify", "task-verify", 0
        );
        VerificationStore.VerificationRecord skipped = stateStore.verifications
                .findById("verification-skipped").orElseThrow();
        assertEquals(VerificationStore.STATUS_SKIPPED, skipped.status());
        assertNull(skipped.command());
        assertNull(skipped.outputExcerpt());
        assertNull(skipped.errorExcerpt());
        assertEquals(0L, skipped.durationMs());
        assertNotNull(skipped.completedAt());

        stateStore.verifications.recordStarted(
                "verification-failed", "run-verify", "task-verify", 0, "mvn test",
                "WORKSPACE_CONTENT_SHA256_V1", "0".repeat(64)
        );
        stateStore.verifications.markFailed(
                "verification-failed", 1, "test output excerpt", "failure excerpt", 120
        );
        VerificationStore.VerificationRecord failed = stateStore.verifications
                .findById("verification-failed").orElseThrow();
        assertEquals(VerificationStore.STATUS_FAILED, failed.status());
        assertEquals(1, failed.exitCode());
        assertEquals("test output excerpt", failed.outputExcerpt());
        assertEquals("failure excerpt", failed.errorExcerpt());

        stateStore.verifications.recordStarted(
                "verification-passed", "run-verify", "task-verify", 1, "mvn test",
                "WORKSPACE_CONTENT_SHA256_V1", "1".repeat(64)
        );
        stateStore.verifications.markPassed(
                "verification-passed", 0, "all tests passed", 90
        );
        assertEquals(VerificationStore.STATUS_PASSED,
                stateStore.verifications.findLatestByRun("run-verify").orElseThrow().status());
        assertEquals(3, stateStore.verifications.findByRun("run-verify").size());
        assertThrows(SQLException.class, () -> stateStore.verifications.markError(
                "verification-passed", "late error", 100
        ));
    }

    @Test
    void verificationSupportsTimeoutAndErrorTerminalStates() throws Exception {
        createTask("task-terminal");
        createRun("run-terminal", "task-terminal", 1);

        stateStore.verifications.recordStarted(
                "verification-timeout", "run-terminal", "task-terminal", 0, "mvn test",
                "WORKSPACE_CONTENT_SHA256_V1", "2".repeat(64)
        );
        stateStore.verifications.markTimedOut(
                "verification-timeout", "partial", "timed out", 300_000
        );
        assertEquals(VerificationStore.STATUS_TIMED_OUT,
                stateStore.verifications.findById("verification-timeout").orElseThrow().status());

        stateStore.verifications.recordStarted(
                "verification-error", "run-terminal", "task-terminal", 1, "mvn test",
                "WORKSPACE_CONTENT_SHA256_V1", "3".repeat(64)
        );
        stateStore.verifications.markError("verification-error", "could not start", 5);
        assertEquals(VerificationStore.STATUS_ERROR,
                stateStore.verifications.findById("verification-error").orElseThrow().status());
    }

    @Test
    void repairAttemptTracksTriggerAndEnforcesAttemptUniqueness() throws Exception {
        createTask("task-repair");
        createRun("run-repair", "task-repair", 2);
        stateStore.verifications.recordStarted(
                "verification-trigger", "run-repair", "task-repair", 0, "mvn test",
                "WORKSPACE_CONTENT_SHA256_V1", "4".repeat(64)
        );
        stateStore.verifications.markFailed(
                "verification-trigger", 1, "failed", null, 10
        );

        stateStore.repairAttempts.recordStarted(
                "repair-001", "run-repair", "task-repair", 1,
                "verification-trigger", "one test failed"
        );
        RepairAttemptStore.RepairAttemptRecord running = stateStore.repairAttempts
                .findById("repair-001").orElseThrow();
        assertEquals(RepairAttemptStore.STATUS_RUNNING, running.status());
        assertEquals("verification-trigger", running.triggerVerificationId());
        assertEquals("one test failed", running.failureSummary());

        stateStore.repairAttempts.markCompleted("repair-001");
        assertEquals(RepairAttemptStore.STATUS_COMPLETED,
                stateStore.repairAttempts.findById("repair-001").orElseThrow().status());
        assertNotNull(stateStore.repairAttempts.findById("repair-001").orElseThrow().completedAt());
        assertEquals(1, stateStore.repairAttempts.findByRun("run-repair").size());
        assertThrows(SQLException.class, () -> stateStore.repairAttempts.markFailed("repair-001"));
        assertThrows(SQLException.class, () -> stateStore.repairAttempts.recordStarted(
                "repair-duplicate", "run-repair", "task-repair", 1,
                "verification-trigger", "duplicate"
        ));
    }

    @Test
    void runtimeStoresRejectMissingOrMismatchedParents() throws Exception {
        createTask("task-parent");
        createTask("task-other");
        createRun("run-parent", "task-parent", 1);

        assertThrows(SQLException.class, () -> stateStore.codingRuns.create(
                "run-missing", "missing-task", CodingRunStore.KIND_INITIAL, null, 1, 0
        ));
        assertThrows(SQLException.class, () -> stateStore.verifications.recordSkipped(
                "verification-mismatch", "run-parent", "task-other", 0
        ));

        stateStore.verifications.recordSkipped(
                "verification-parent", "run-parent", "task-parent", 0
        );
        assertThrows(SQLException.class, () -> stateStore.repairAttempts.recordStarted(
                "repair-mismatch", "run-parent", "task-other", 1,
                "verification-parent", "wrong task"
        ));
    }

    private void createTask(String taskId) throws SQLException {
        stateStore.tasks.createTask(taskId, "runtime test", "mock", null, "on-request", 10);
    }

    private void createRun(String runId, String taskId, int maxRepairAttempts) throws SQLException {
        stateStore.codingRuns.create(
                runId, taskId, CodingRunStore.KIND_INITIAL, null, maxRepairAttempts, 0
        );
    }
}
