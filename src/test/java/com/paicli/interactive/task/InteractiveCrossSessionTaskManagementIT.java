package com.paicli.interactive.task;

import com.paicli.config.Config;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.InteractiveProposalStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveCrossSessionTaskManagementIT {
    @TempDir Path temp;

    @Test
    void newSessionHydratesLatestReportDiffAndRewindsPersistedSnapshot() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path source = workspace.resolve("src/main/java/demo/Sample.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package demo; class Sample { int value() { return 1; } }\n");
        InteractiveSession first = session("session-a", workspace, "auto");
        try (InteractiveRuntimeAdapter runtime = adapter(workspace)) {
            runtime.openSession(first);
            first.startTask("task-verified", "run-verified");
            persistVerifiedEdit(workspace, source);
            first.finishTask("task-verified");
        }

        InteractiveSession second = session("session-b", workspace, "off");
        try (InteractiveRuntimeAdapter runtime = adapter(workspace)) {
            runtime.openSession(second);
            StateStoreInteractiveQueryFacade queries =
                    new StateStoreInteractiveQueryFacade(workspace, runtime.view());

            assertTrue(queries.tasks(List.of("recent")).stream()
                    .anyMatch(line -> line.contains("COMPLETED_VERIFIED")));
            List<String> status = queries.status(
                    second.snapshot(), List.of("--details"));
            assertTrue(status.contains("Current session Code Intelligence: NOT_RUN"));
            assertTrue(status.contains("Latest persisted status: COMPLETED_VERIFIED"));
            assertTrue(status.contains("Latest persisted verification: PASSED"));

            List<String> latest = queries.report(second.snapshot(), "latest");
            assertTrue(latest.contains("  update Sample"), latest.toString());
            assertTrue(latest.stream().anyMatch(line->line.contains("[Task]")));
            assertFalse(latest.stream().anyMatch(line->line.contains("Projection relation")));
            assertFalse(latest.stream().anyMatch(line->line.contains("Code Intelligence")));
            assertFalse(latest.stream().anyMatch(line->line.contains("task-verified")));
            assertEquals(latest, queries.report(second.snapshot(), null));
            assertEquals(List.of("No active task report."),
                    queries.report(second.snapshot(), "current"));

            List<String> beforeRewind = queries.diff(second.snapshot());
            assertTrue(beforeRewind.stream().anyMatch(line ->
                    line.startsWith("APPLIED src/main/java/demo/Sample.java")));
            assertTrue(beforeRewind.contains("Snapshot: present"));
            assertTrue(queries.latestTaskWithSnapshots().isPresent());

            Queue<String> rewindInputs = new ArrayDeque<>(List.of("1", "y"));
            InteractiveTaskCommandService commands = new InteractiveTaskCommandService(
                    workspace, queries, runtime, prompt -> rewindInputs.remove());
            var rewind = commands.rewind(List.of(), second);
            assertEquals("REWIND_COMPLETED", rewind.code(),
                    String.join(" | ", rewind.lines()));
            assertTrue(rewindInputs.isEmpty());
            assertTrue(Files.readString(source).contains("return 1"));

            List<String> afterRewind = queries.diff(second.snapshot());
            assertEquals("No current effective changes.", afterRewind.getFirst());
            assertTrue(afterRewind.stream().anyMatch(line ->
                    line.startsWith("REWOUND (historical) ")));
            assertTrue(queries.report(second.snapshot(), "latest").contains("  update Sample"));
            assertFalse(queries.report(second.snapshot(), "latest").stream()
                    .anyMatch(line->line.contains("Current workspace verification")));
            assertTrue(queries.status(second.snapshot(), List.of("--details")).contains(
                    "Current workspace verification: STALE_AFTER_REWIND"));
            try (StateStore state = new StateStore(workspace)) {
                assertEquals(InteractiveProposalStore.REWOUND,
                        state.interactiveProposals.findByTask("task-verified", 10)
                                .getFirst().status());
            }
        }

        Path otherWorkspace = temp.resolve("other-workspace");
        Files.createDirectories(otherWorkspace);
        StateStoreInteractiveQueryFacade isolated = new StateStoreInteractiveQueryFacade(
                otherWorkspace, new InteractiveRuntimeView());
        assertEquals(List.of("No matching persisted tasks."), isolated.tasks(List.of("recent")));
        assertEquals(List.of("No current or recent task report."),
                isolated.report(session("session-c", otherWorkspace, "off").snapshot(), "latest"));
        assertEquals(List.of("No proposed or applied changes."),
                isolated.diff(session("session-d", otherWorkspace, "off").snapshot()));
    }

    private static InteractiveRuntimeAdapter adapter(Path workspace) throws Exception {
        return InteractiveRuntimeAdapter.open(workspace,
                (taskId, request, state, bridge) -> new CodingTaskResult(
                        taskId, null, null, TaskStore.STATUS_FAILED,
                        null, 0, "not executed"),
                event -> { });
    }

    private static InteractiveSession session(String id, Path workspace, String verification) {
        InteractiveSession session = new InteractiveSession(id, workspace,
                Config.PROVIDER_MOCK, "coder", Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH,
                verification, "MAVEN_TEST_V1");
        session.start();
        return session;
    }

    private static void persistVerifiedEdit(Path workspace, Path source) throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-verified", "update Sample", "ollama", "coder",
                    "on-request", 4);
            state.tasks.markRunning("task-verified");
            state.codingRuns.create("run-verified", "task-verified",
                    CodingRunStore.KIND_INITIAL, "MAVEN_TEST_V1", 1, 0);
            state.plans.recordGenerated("plan-verified", "run-verified",
                    "task-verified", "Read Sample\nEdit Sample\nVerify");
            state.plans.markActive("plan-verified");
            state.plans.markCompleted("plan-verified");
            state.interactiveTaskFacts.recordCodeIntelligence(
                    "task-verified", "run-verified", "USED", 1, 1, 1);

            FileSnapshotService snapshots = new FileSnapshotService(workspace, state.snapshots);
            String snapshotId = snapshots.captureBeforeWrite(
                    "task-verified", null, Path.of("src/main/java/demo/Sample.java"));
            assertEquals("src/main/java/demo/Sample.java",
                    state.snapshots.findById(snapshotId).orElseThrow().filePath());
            String changed = Files.readString(source).replace("return 1", "return 2");
            String changedHash = sha256(changed);
            Files.writeString(source, changed);
            snapshots.markAppliedAfterWrite(snapshotId,
                    Path.of("src/main/java/demo/Sample.java"));
            state.toolCalls.record("task-verified", 1, null, "write_file",
                    "{\"path\":\"src/main/java/demo/Sample.java\","
                            + "\"content\":\"<redacted sha256=" + changedHash
                            + " bytes=" + changed.getBytes(StandardCharsets.UTF_8).length
                            + ">\",\"overwrite\":true}",
                    ToolResult.success("written"));
            state.codingRuns.updateLastIteration("run-verified", 1);

            state.codingRuns.transitionPhase("run-verified",
                    CodingRunStore.PHASE_PLANNING, CodingRunStore.PHASE_EXECUTING);
            state.codingRuns.transitionPhase("run-verified",
                    CodingRunStore.PHASE_EXECUTING, CodingRunStore.PHASE_VERIFYING);
            state.verifications.recordStarted("verify-1", "run-verified",
                    "task-verified", 0, "MAVEN_TEST_V1","WORKSPACE_CONTENT_SHA256_V1",
                    "a".repeat(64));
            state.verifications.markPassed("verify-1", 0, "2 tests passed", 10);
            state.codingRuns.markCompleted("run-verified");
            state.tasks.markDone("task-verified");
            state.tasks.markInteractiveTerminal(
                    "task-verified", TaskStore.STATUS_COMPLETED_VERIFIED);
        }
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
