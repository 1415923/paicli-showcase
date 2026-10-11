package com.paicli.interactive.query;

import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveQueryFacadeTest {
    @TempDir Path temp;

    @Test void readsBoundedPlanTasksStatusAndReportFromStores() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        String taskId = "query-task";
        String runId = "query-run";
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask(taskId, "fix query behavior", "mock",
                    null, "read-only", 3);
            state.tasks.markRunning(taskId);
            state.codingRuns.create(runId, taskId, CodingRunStore.KIND_INITIAL,
                    null, 1, 0);
            state.plans.recordGenerated("plan-1", runId, taskId,
                    "1. Inspect\n2. Change\n3. Verify");
        }

        InteractiveSession session = new InteractiveSession(
                "session-1", workspace, "mock", null, "read-only",
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        session.startTask(taskId, runId);
        session.finishTask(taskId);
        InteractiveRuntimeView view = new InteractiveRuntimeView();
        view.taskStarted(taskId, runId);
        view.phase("VERIFYING");
        view.codeContext(4, 2);
        view.taskFinished(taskId, runId, "DONE");

        StateStoreInteractiveQueryFacade queries =
                new StateStoreInteractiveQueryFacade(workspace, view);
        assertTrue(queries.status(session.snapshot(), List.of("--details")).stream()
                .anyMatch(line -> line.equals("Current session Code Intelligence: USED")));
        assertTrue(queries.status(session.snapshot(), List.of("provider")).stream()
                .anyMatch(line -> line.startsWith("Projection:")),
                "the frozen /status provider detail surface must remain available");
        assertEquals(List.of("Plan status: GENERATED", "1. Inspect",
                        "2. Change", "3. Verify"),
                queries.plan(session.snapshot()));
        assertTrue(queries.tasks(10).stream()
                .anyMatch(line -> line.contains("fix query behavior")));
        List<String> report = queries.report(session.snapshot(), null);
        assertTrue(report.contains("  fix query behavior"));
        assertFalse(report.stream().anyMatch(line->line.contains("query-task")));
        assertFalse(report.stream().anyMatch(line->line.contains("Projection relation")));
    }

    @Test void defaultStatusIsBoundedDecisionSnapshotWithoutInternalDump()
            throws Exception {
        Path workspace = temp.resolve("status-snapshot");
        Files.createDirectories(workspace);
        InteractiveSession session = new InteractiveSession(
                "session-status", workspace, "ollama", "qwen3:14b", "read-only",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();

        List<String> lines = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).status(session.snapshot());
        String output = String.join("\n", lines);

        assertTrue(lines.size() <= 30, output);
        assertTrue(lines.stream().allMatch(line ->
                line.codePointCount(0, line.length()) <= 80), output);
        assertTrue(lines.getFirst().contains("PaiCLI 1.2.0 · Status"));
        assertTrue(lines.getLast().matches("─+"));
        assertTrue(output.contains("  Workspace"));
        assertTrue(output.contains("status-snapshot"));
        assertTrue(output.contains("  Provider"));
        assertTrue(output.contains("Ollama · qwen3:14b"));
        assertTrue(output.contains("  Next"));
        assertTrue(lines.stream().allMatch(line -> line.isEmpty()
                || line.startsWith("  ") || line.matches("─+.*")), output);
        assertFalse(output.matches("(?s).*\\b(?:thr|tsk|run|gen|pln)_[a-z0-9]+\\b.*"));
        for (String internal : List.of("NOT_APPLICABLE", "ABSENT", "UNKNOWN",
                "NOT_EXPOSED", "Provider projection:", "Generation:",
                "Current session runtime phase:", "Latest persisted")) {
            assertFalse(output.contains(internal), internal + " in:\n" + output);
        }
    }

    @Test void taskListingUsesLimitPlusOneAndStableTruncation() throws Exception {
        Path workspace = temp.resolve("many");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            for (int i = 0; i < 25; i++) {
                state.tasks.createTask("task-" + String.format("%02d", i),
                        "goal-" + i, "mock", null, "read-only", 1);
            }
        }
        StateStoreInteractiveQueryFacade queries =
                new StateStoreInteractiveQueryFacade(workspace, new InteractiveRuntimeView());
        List<String> lines = queries.tasks(5);
        assertEquals(16, lines.size());
        assertTrue(lines.getFirst().startsWith("1. "));
        assertEquals("[truncated]", lines.getLast());
        assertFalse(String.join("\n", lines).contains(temp.toAbsolutePath().toString()));
        assertFalse(String.join("\n", lines).contains("original_bytes"));
    }

    @Test void taskShortIdsRemainDistinguishableAndUseHumanEllipsis() throws Exception {
        Path workspace = temp.resolve("short-ids");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("interactive-aaaaaaaa-11111111", "first", "ollama",
                    "coder", "read-only", 1);
            state.tasks.createTask("interactive-aaaaaaaa-22222222", "second", "ollama",
                    "coder", "read-only", 1);
        }
        List<InteractiveTaskView> tasks = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).taskViews("recent", 10);
        assertEquals(2, tasks.stream().map(InteractiveTaskView::safeTaskId)
                .distinct().count());
        assertTrue(tasks.stream().allMatch(task -> task.safeTaskId().contains("…")));
        assertTrue(tasks.stream().anyMatch(task -> task.safeTaskId().endsWith("11111111")));
        assertTrue(tasks.stream().anyMatch(task -> task.safeTaskId().endsWith("22222222")));
    }

    @Test void filtersTasksAndExposesBoundedRecoveryFacts() throws Exception {
        Path workspace = temp.resolve("filters");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("failed-task", "repair me", "ollama", "coder",
                    "on-request", 3);
            state.tasks.markFailed("failed-task", "bounded failure");
            state.events.append("failed-task", 0,
                    com.paicli.model.AgentMessage.userGoal("repair me"));
            state.tasks.createTask("done-task", "already done", "ollama", "coder",
                    "on-request", 3);
            state.tasks.markDone("done-task");
        }
        StateStoreInteractiveQueryFacade queries =
                new StateStoreInteractiveQueryFacade(workspace, new InteractiveRuntimeView());
        assertEquals(List.of("failed-task"), queries.taskViews("failed", 10).stream()
                .map(InteractiveTaskView::taskId).toList());
        assertEquals(List.of("failed-task"), queries.taskViews("recoverable", 10).stream()
                .map(InteractiveTaskView::taskId).toList());
        assertTrue(queries.tasks(List.of("failed-task")).stream()
                .anyMatch(line -> line.contains("repair me")));
    }

    @Test void reportRedactsSecretAndAbsolutePathFromLegacyMetadata() throws Exception {
        Path workspace = temp.resolve("redaction");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-secret", "inspect C:\\private\\secret.txt API_KEY=value",
                    "ollama", "coder", "on-request", 3);
            state.tasks.markFailed("task-secret", "Bearer sentinel-token at /home/user/private");
        }
        InteractiveSession session = new InteractiveSession("session-redact", workspace,
                "ollama", "coder", "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.EPOCH);
        session.start();
        String report = String.join("\n", new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).report(session.snapshot(), "task-secret"));
        assertEquals("Report selector invalid.", report);
        assertFalse(report.contains("value"));
        assertFalse(report.contains("sentinel-token"));
        assertFalse(report.contains("C:\\private"));
        assertFalse(report.contains("/home/user"));
    }

    @Test void cancelledRejectedProposalDoesNotCountAsAppliedWrite() throws Exception {
        Path workspace = temp.resolve("cancel-report");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-cancel", "change A", "ollama", "coder",
                    "on-request", 3);
            state.tasks.markRunning("task-cancel");
            state.codingRuns.create("run-cancel", "task-cancel",
                    CodingRunStore.KIND_INITIAL, null, 1, 0);
            state.plans.recordGenerated("plan-cancel", "run-cancel", "task-cancel", "Inspect A");
            state.plans.markActive("plan-cancel");
            state.plans.markCompleted("plan-cancel");
            state.toolCalls.record("task-cancel", 1, "write_file", "{}",
                    ToolResult.error("[DENIED] interactive user cancelled task"));
            state.approvals.record("task-cancel", 1, "approval-1", "write_file",
                    "HIGH", "DENY", "interactive user cancelled task");
            state.interactiveProposals.recordProposed(
                    "proposal-cancel", "task-cancel", "run-cancel",
                    "src/A.java", "a".repeat(64), 1, 1, false, false);
            state.interactiveProposals.resolve(
                    "proposal-cancel", "PROPOSED", "CANCELLED");
            state.codingRuns.markCancelled("run-cancel", false);
            state.tasks.markCancelled("task-cancel", false,
                    "Cancellation reason: user requested");
        }
        InteractiveSession session = new InteractiveSession("session-cancel", workspace,
                "ollama", "coder", "on-request", InteractiveSession.TerminalMode.PLAIN,
                Instant.EPOCH);
        session.start();
        List<String> report = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).report(
                        session.snapshot(), "task-cancel");
        assertEquals(List.of("Report selector invalid."), report);
    }
}
