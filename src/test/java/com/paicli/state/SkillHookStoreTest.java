package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillHookStoreTest {

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
    void skillLoadsPersistTerminalOutcomesAndRejectDuplicatePaths()
            throws Exception {
        createTaskAndRun("task-load", "run-load");

        stateStore.skillLoads.recordLoaded(
                "load-1", "skill-java", "run-load", "task-load",
                "java-testing", "skills/java-testing/SKILL.md",
                "Java test workflow", "sha-java");
        stateStore.skillLoads.recordRejected(
                "load-2", "skill-bad", "run-load", "task-load",
                "bad-skill", "skills/bad-skill/SKILL.md",
                "metadata rejected");
        stateStore.skillLoads.recordError(
                "load-3", "skill-error", "run-load", "task-load",
                null, ".paicli/skills/error/SKILL.md",
                "could not read skill");

        List<SkillLoadStore.SkillLoadRecord> loads =
                stateStore.skillLoads.findByRun("run-load");
        assertEquals(3, loads.size());
        assertEquals(SkillLoadStore.STATUS_LOADED, loads.getFirst().status());
        assertEquals("sha-java", loads.getFirst().contentSha256());
        assertEquals(SkillLoadStore.STATUS_ERROR, loads.getLast().status());
        assertNotNull(loads.getLast().loadedAt());
        assertEquals(1, stateStore.skillLoads
                .findByStatus(SkillLoadStore.STATUS_REJECTED).size());
        assertEquals("load-1", stateStore.skillLoads.findByRunAndPath(
                "run-load", "skills/java-testing/SKILL.md")
                .orElseThrow().skillLoadId());

        assertThrows(SQLException.class, () -> stateStore.skillLoads.recordLoaded(
                "load-duplicate", "skill-other", "run-load", "task-load",
                "other", "skills/java-testing/SKILL.md",
                "duplicate path", "sha-other"));
    }

    @Test
    void skillSelectionsAuditZeroAndSelectedSkillsAtomically()
            throws Exception {
        createTaskAndRun("task-select", "run-select");
        recordLoadedSkill("load-a", "skill-a", "alpha",
                "skills/alpha/SKILL.md", "run-select", "task-select");
        recordLoadedSkill("load-b", "skill-b", "beta",
                "skills/beta/SKILL.md", "run-select", "task-select");

        stateStore.skillSelections.recordCompleted(
                "selection-plan", "run-select", "task-select",
                SkillSelectionStore.STAGE_PLAN, "plan", "input-plan",
                List.of());
        SkillSelectionStore.SkillSelectionRecord zero =
                stateStore.skillSelections.findSelectionById("selection-plan")
                        .orElseThrow();
        assertEquals(0, zero.selectedCount());
        assertTrue(stateStore.skillSelections
                .findSelectedBySelection("selection-plan").isEmpty());

        stateStore.skillSelections.recordCompleted(
                "selection-execute", "run-select", "task-select",
                SkillSelectionStore.STAGE_EXECUTE, "execute", "input-execute",
                List.of(
                        new SkillSelectionStore.SelectedSkillInput(
                                "selected-a", "load-a", "alpha", 1, 10.5,
                                "goal keyword matched", "alpha context"),
                        new SkillSelectionStore.SelectedSkillInput(
                                "selected-b", "load-b", "beta", 2, 5.0,
                                "plan keyword matched", "beta context")
                ));

        SkillSelectionStore.SkillSelectionRecord selection =
                stateStore.skillSelections.findByOccurrence(
                        "run-select", SkillSelectionStore.STAGE_EXECUTE,
                        "execute").orElseThrow();
        assertEquals(2, selection.selectedCount());
        List<SkillSelectionStore.SelectedSkillRecord> selected =
                stateStore.skillSelections.findSelectedBySelection(
                        "selection-execute");
        assertEquals(List.of("alpha", "beta"),
                selected.stream().map(
                        SkillSelectionStore.SelectedSkillRecord::skillName)
                        .toList());
        assertEquals("alpha context", selected.getFirst().contextExcerpt());
        assertEquals(2, stateStore.skillSelections
                .findSelectionsByRun("run-select").size());
        assertEquals(2, stateStore.skillSelections
                .findSelectedByRun("run-select").size());

        assertThrows(SQLException.class,
                () -> stateStore.skillSelections.recordCompleted(
                        "selection-invalid", "run-select", "task-select",
                        SkillSelectionStore.STAGE_REPAIR, "repair:1",
                        "input-repair",
                        List.of(new SkillSelectionStore.SelectedSkillInput(
                                "selected-missing", "missing-load", "missing",
                                1, 1.0, "invalid parent", "context"))));
        assertFalse(stateStore.skillSelections
                .findSelectionById("selection-invalid").isPresent());
    }

    @Test
    void skillSelectionErrorsRemainAuditable() throws Exception {
        createTaskAndRun("task-error", "run-error");

        stateStore.skillSelections.recordError(
                "selection-error", "run-error", "task-error",
                SkillSelectionStore.STAGE_REPAIR, "repair:2",
                "input-hash", "selector failed");

        SkillSelectionStore.SkillSelectionRecord error =
                stateStore.skillSelections.findSelectionById(
                        "selection-error").orElseThrow();
        assertEquals(SkillSelectionStore.STATUS_ERROR, error.status());
        assertEquals(0, error.selectedCount());
        assertEquals("selector failed", error.errorExcerpt());
    }

    @Test
    void hookRunsTrackLifecycleAndEnforceOccurrenceIdempotency()
            throws Exception {
        createTaskAndRun("task-hook", "run-hook");

        stateStore.hookRuns.recordStarted(
                "hook-pass", "run-hook", "task-hook", "format",
                HookRunStore.EVENT_BEFORE_VERIFY, "verify:0", 1,
                "mvn test", HookRunStore.POLICY_FAIL);
        stateStore.hookRuns.markPassed(
                "hook-pass", 0, "passed", null, 25);
        HookRunStore.HookRunRecord passed = stateStore.hookRuns
                .findByOccurrence(
                        "run-hook", HookRunStore.EVENT_BEFORE_VERIFY,
                        "verify:0", "format").orElseThrow();
        assertEquals(HookRunStore.STATUS_PASSED, passed.status());
        assertEquals(0, passed.exitCode());
        assertEquals(25L, passed.durationMs());
        assertNotNull(passed.completedAt());

        stateStore.hookRuns.recordStarted(
                "hook-warn", "run-hook", "task-hook", "report",
                HookRunStore.EVENT_AFTER_VERIFY, "verify:0", 0,
                "echo report", HookRunStore.POLICY_WARN);
        stateStore.hookRuns.markWarned(
                "hook-warn", 1, null, "warning", 5);
        assertEquals(HookRunStore.STATUS_WARNED,
                stateStore.hookRuns.findById("hook-warn")
                        .orElseThrow().status());
        assertEquals(1, stateStore.hookRuns
                .findByStatus(HookRunStore.STATUS_WARNED).size());
        assertEquals(1, stateStore.hookRuns
                .findByEvent(HookRunStore.EVENT_AFTER_VERIFY).size());

        assertThrows(SQLException.class,
                () -> stateStore.hookRuns.markFailed(
                        "hook-pass", 2, null, "late failure", 30));
        assertThrows(SQLException.class,
                () -> stateStore.hookRuns.recordStarted(
                        "hook-duplicate", "run-hook", "task-hook", "format",
                        HookRunStore.EVENT_BEFORE_VERIFY, "verify:0", 2,
                        "mvn test", HookRunStore.POLICY_FAIL));
        List<HookRunStore.HookRunRecord> hookRuns =
                stateStore.hookRuns.findByRun("run-hook");
        assertEquals(List.of("hook-pass", "hook-warn"),
                hookRuns.stream().map(HookRunStore.HookRunRecord::hookRunId)
                        .toList());
    }

    @Test
    void hookRunsSupportAllNonSuccessTerminalStates() throws Exception {
        createTaskAndRun("task-terminal", "run-terminal");

        startHook("hook-failed", "run-terminal", "task-terminal", "failed", 0);
        stateStore.hookRuns.markFailed(
                "hook-failed", 2, "partial", "failed", 10);
        startHook("hook-blocked", "run-terminal", "task-terminal", "blocked", 1);
        stateStore.hookRuns.markBlocked("hook-blocked", "blocked", 0);
        startHook("hook-timeout", "run-terminal", "task-terminal", "timeout", 2);
        stateStore.hookRuns.markTimedOut(
                "hook-timeout", "partial", "timed out", 30_000);
        startHook("hook-error", "run-terminal", "task-terminal", "error", 3);
        stateStore.hookRuns.markError(
                "hook-error", null, "could not start", 2);
        startHook(
                "hook-interrupted", "run-terminal", "task-terminal",
                "interrupted", 4);
        stateStore.hookRuns.markInterrupted(
                "hook-interrupted", "interrupted before resume", 5);

        assertEquals(HookRunStore.STATUS_FAILED,
                stateStore.hookRuns.findById("hook-failed")
                        .orElseThrow().status());
        assertEquals(HookRunStore.STATUS_BLOCKED,
                stateStore.hookRuns.findById("hook-blocked")
                        .orElseThrow().status());
        assertEquals(HookRunStore.STATUS_TIMED_OUT,
                stateStore.hookRuns.findById("hook-timeout")
                        .orElseThrow().status());
        assertEquals(HookRunStore.STATUS_ERROR,
                stateStore.hookRuns.findById("hook-error")
                        .orElseThrow().status());
        HookRunStore.HookRunRecord interrupted = stateStore.hookRuns
                .findById("hook-interrupted").orElseThrow();
        assertEquals(HookRunStore.STATUS_INTERRUPTED, interrupted.status());
        assertNull(interrupted.exitCode());
        assertEquals(5, stateStore.hookRuns.findByRun("run-terminal").size());
    }

    @Test
    void storesRejectMissingOrMismatchedTaskRunParents() throws Exception {
        createTaskAndRun("task-parent", "run-parent");
        stateStore.tasks.createTask(
                "task-other", "other", "mock", null, "on-request", 10);

        assertThrows(SQLException.class, () -> stateStore.skillLoads.recordLoaded(
                "load-missing", "skill", "missing-run", "task-parent",
                "skill", "skills/skill/SKILL.md", null, "sha"));
        assertThrows(SQLException.class, () -> stateStore.skillLoads.recordLoaded(
                "load-mismatch", "skill", "run-parent", "task-other",
                "skill", "skills/skill/SKILL.md", null, "sha"));
        assertThrows(SQLException.class,
                () -> stateStore.skillSelections.recordError(
                        "selection-mismatch", "run-parent", "task-other",
                        SkillSelectionStore.STAGE_PLAN, "plan", null,
                        "wrong owner"));
        assertThrows(SQLException.class, () -> stateStore.hookRuns.recordStarted(
                "hook-mismatch", "run-parent", "task-other", "hook",
                HookRunStore.EVENT_BEFORE_PLAN, "plan", 0,
                "echo test", HookRunStore.POLICY_WARN));
        assertThrows(SQLException.class, () -> stateStore.hookRuns.recordStarted(
                "hook-invalid-event", "run-parent", "task-parent", "hook",
                "UNKNOWN", "plan", 0, "echo test",
                HookRunStore.POLICY_WARN));
    }

    private void createTaskAndRun(String taskId, String runId)
            throws SQLException {
        stateStore.tasks.createTask(
                taskId, "skill hook test", "mock", null, "on-request", 10);
        stateStore.codingRuns.create(
                runId, taskId, CodingRunStore.KIND_INITIAL, null, 1, 0);
    }

    private void recordLoadedSkill(
            String loadId, String skillId, String name, String path,
            String runId, String taskId) throws SQLException {
        stateStore.skillLoads.recordLoaded(
                loadId, skillId, runId, taskId, name, path,
                name + " description", "sha-" + name);
    }

    private void startHook(String hookRunId, String runId, String taskId,
                           String occurrenceKey, int sequenceNo)
            throws SQLException {
        stateStore.hookRuns.recordStarted(
                hookRunId, runId, taskId, hookRunId,
                HookRunStore.EVENT_BEFORE_FINISH, occurrenceKey, sequenceNo,
                "echo test", HookRunStore.POLICY_FAIL);
    }
}
