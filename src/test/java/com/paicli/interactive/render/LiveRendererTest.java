package com.paicli.interactive.render;

import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.task.TaskSubmissionResult;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class LiveRendererTest {
    @Test void rendersBoundedTranscriptAndCoalescesHighFrequencyEvents() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);

        publisher.publish(AgentEventType.TASK_STARTED, "task-1", "run-1",
                m -> new AgentEvents.TaskStarted(m, "fix rounding"));
        publisher.publish(AgentEventType.PLAN_CREATED, "task-1", "run-1",
                m -> new AgentEvents.PlanCreated(m, "inspect and verify", 3,
                        java.util.List.of("inspect", "edit", "verify")));
        for (int i = 0; i < 8; i++) {
            publisher.publish(AgentEventType.FILE_READ, "task-1", "run-1",
                    m -> new AgentEvents.FileRead(m, "src/A.java", 12));
        }
        publisher.publish(AgentEventType.DIFF_PROPOSED, "task-1", "run-1",
                m -> new AgentEvents.DiffProposed(m, "src/A.java", 2, 1,
                        "replacement-line summary", false));
        publisher.publish(AgentEventType.SNAPSHOT_CREATED, "task-1", "run-1",
                m -> new AgentEvents.SnapshotCreated(m, "src/A.java", "snapshot"));
        publisher.publish(AgentEventType.FILE_WRITTEN, "task-1", "run-1",
                m -> new AgentEvents.FileWritten(m, "src/A.java", 42));
        publisher.publish(AgentEventType.VERIFICATION_FAILED, "task-1", "run-1",
                m -> new AgentEvents.VerificationFailed(
                        m, "TEST_FAILURE", 3, 1, 0, 0, false, true, "one failed"));
        publisher.publish(AgentEventType.REPAIR_STARTED, "task-1", "run-1",
                m -> new AgentEvents.RepairStarted(m, 1));
        publisher.publish(AgentEventType.VERIFICATION_PASSED, "task-1", "run-1",
                m -> new AgentEvents.VerificationPassed(
                        m, 3, 0, 0, 0, true, "passed"));
        publisher.publish(AgentEventType.TASK_COMPLETED, "task-1", "run-1",
                m -> new AgentEvents.TaskCompleted(m, "COMPLETED_VERIFIED",
                        "PASSED", "changed rounding safely"));
        renderer.flush();

        String output = buffer.toString();
        assertFalse(output.contains("Task · Planning"));
        assertTrue(output.contains("[Task] Started: fix rounding"));
        assertTrue(output.contains("[Plan] 3 steps"));
        assertTrue(output.contains("[Proposed Edit] src/A.java +2 -1"));
        assertTrue(output.contains("[Snapshot] src/A.java"));
        assertTrue(output.contains("[Verify] failed"));
        assertTrue(output.contains("[Repair] attempt 1"));
        assertTrue(output.contains("[Verify] passed"));
        assertTrue(output.contains("[Task] Completed"));
        assertTrue(output.lines().anyMatch(line ->
                line.startsWith("[Task] Completed · ")));
        assertFalse(output.contains("─ Completed"));
        assertTrue(output.contains("Files changed: 1"));
        assertTrue(output.contains("Tests: 3 passed"));
        assertTrue(output.contains("Snapshots: 1"));
        assertFalse(output.contains("[Task] changed rounding safely"));
        assertFalse(output.contains("[Assistant]"));
        assertFalse(output.contains("[Result]"));
        assertFalse(output.contains("[Done]"));
        assertTrue(output.contains("repeated event(s) omitted"));
        assertFalse(output.contains("{\""));
        assertFalse(output.contains("D:\\"));
        assertTrue(output.length() < 8192);
    }

    @Test void commandAndNewFileApprovalsUseActionSpecificLanguage() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.APPROVAL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalRequested(m, "run_command", null,
                        0, 0, false, "COMMAND", "Command: mvn test", "high risk"));
        publisher.publish(AgentEventType.APPROVAL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalRequested(m, "write_file", "src/New.java",
                        4, 0, false, "NEW_FILE", "src/New.java +4 -0",
                        "NEW FILE"));
        publisher.publish(AgentEventType.APPROVAL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalRequested(m, "write_file", "src/A.java",
                        2, 1, false, "EDIT", "src/A.java +2 -1", null));
        renderer.flush();
        String output = buffer.toString();
        assertTrue(output.contains("[Proposed Command]"));
        assertFalse(output.contains("Run command?"));
        assertFalse(output.contains("[Proposed Command] +0 -0"));
        assertTrue(output.contains("[Proposed New File] NEW FILE src/New.java +4 -0"));
        assertFalse(output.contains("Create file?"));
        assertEquals(1, output.split("src/New\\.java", -1).length - 1);
        assertEquals(1, output.split("src/A\\.java", -1).length - 1);
        assertFalse(output.contains("all-safe"));
    }

    @Test void preWriteDiffAndApprovalRenderOnePathAndOneStatisticsSummary() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.DIFF_PROPOSED, "task-1", "run-1",
                m -> new AgentEvents.DiffProposed(m, "src/A.java", 2, 1,
                        "pre-write proposal", false));
        publisher.publish(AgentEventType.APPROVAL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalRequested(m, "write_file", "src/A.java",
                        2, 1, false, "EDIT", null, null));
        renderer.flush();

        String output = buffer.toString();
        assertEquals(1, output.split("src/A\\.java", -1).length - 1);
        assertEquals(1, output.split("\\+2 -1", -1).length - 1);
        assertTrue(output.contains("[Proposed Edit] src/A.java +2 -1"));
    }

    @Test void skippedVerificationAndUnverifiedTerminalNeverClaimTestsPassed() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.VERIFICATION_SKIPPED, "task-1", "run-1",
                m -> new AgentEvents.VerificationSkipped(m,
                        "Structured verification was disabled."));
        publisher.publish(AgentEventType.TASK_COMPLETED, "task-1", "run-1",
                m -> new AgentEvents.TaskCompleted(m, "COMPLETED_UNVERIFIED",
                        "SKIPPED", "Changes were applied but structured verification was disabled."));
        renderer.flush();
        String output = buffer.toString();
        assertTrue(output.contains("[Verify] skipped"));
        assertTrue(output.contains("[Task] Completed"));
        assertTrue(output.contains("Verification: SKIPPED"));
        assertFalse(output.contains("passed tests=unknown"));
    }

    @Test void compactHidesRuntimeNoiseAndVerboseRestoresIt() {
        StringWriter buffer = new StringWriter();
        RuntimeDisplayPreferences preferences = new RuntimeDisplayPreferences();
        PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                new PrintWriter(buffer), preferences);
        AgentEventPublisher publisher = new AgentEventPublisher(
                "session-1", Clock.systemUTC());
        publisher.addSink(renderer);

        publisher.publish(AgentEventType.PHASE_CHANGED, "task-1", "run-1",
                m -> new AgentEvents.PhaseChanged(m, "EXECUTING"));
        publisher.publish(AgentEventType.TOOL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ToolRequested(m, "read_file", "src/A.java"));
        publisher.publish(AgentEventType.TOOL_APPROVED, "task-1", "run-1",
                m -> new AgentEvents.ToolApproved(m, "read_file", "policy"));
        publisher.publish(AgentEventType.APPROVAL_RESOLVED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalResolved(m, "write_file", "APPLY", false));
        assertTrue(buffer.toString().contains("• Running read_file"));

        preferences.setVerbose(true);
        publisher.publish(AgentEventType.PHASE_CHANGED, "task-1", "run-1",
                m -> new AgentEvents.PhaseChanged(m, "VERIFYING"));
        publisher.publish(AgentEventType.TOOL_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.ToolRequested(m, "read_file", "src/B.java"));
        publisher.publish(AgentEventType.TOOL_APPROVED, "task-1", "run-1",
                m -> new AgentEvents.ToolApproved(m, "read_file", "policy"));
        publisher.publish(AgentEventType.TOOL_REJECTED, "task-1", "run-1",
                m -> new AgentEvents.ToolRejected(m, "write_file", "outside scope"));
        publisher.publish(AgentEventType.APPROVAL_RESOLVED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalResolved(m, "write_file", "APPLY", false));
        publisher.publish(AgentEventType.APPROVAL_RESOLVED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalResolved(m, "write_file", "DENY", false));
        publisher.publish(AgentEventType.APPROVAL_RESOLVED, "task-1", "run-1",
                m -> new AgentEvents.ApprovalResolved(m, "write_file", "CANCELLED", false));

        String output = buffer.toString();
        assertTrue(output.contains("[Phase] VERIFYING"));
        assertTrue(output.contains("• Running read_file"));
        assertTrue(output.contains("[Tool] read_file approved"));
        assertTrue(output.contains("[Tool] write_file rejected: outside scope"));
        assertTrue(output.contains("[Approval] approved"));
        assertTrue(output.contains("[Approval] rejected"));
        assertTrue(output.contains("[Approval] cancelled"));
        assertFalse(output.contains("EXECUTING"));
        assertTrue(output.contains("src/A.java"));
    }

    @Test void agentSummaryCannotReplaceStructuredTerminalFacts() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher(
                "session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_STARTED, "task-1", "run-1",
                m -> new AgentEvents.TaskStarted(m, "change formatter"));
        publisher.publish(AgentEventType.FILE_WRITTEN, "task-1", "run-1",
                m -> new AgentEvents.FileWritten(m, "src/A.java", 12));
        publisher.publish(AgentEventType.SNAPSHOT_CREATED, "task-1", "run-1",
                m -> new AgentEvents.SnapshotCreated(m, "src/A.java", "snapshot"));
        publisher.publish(AgentEventType.VERIFICATION_PASSED, "task-1", "run-1",
                m -> new AgentEvents.VerificationPassed(
                        m, 2, 0, 0, 0, true, "passed"));
        publisher.publish(AgentEventType.TASK_COMPLETED, "task-1", "run-1",
                m -> new AgentEvents.TaskCompleted(m, "COMPLETED_VERIFIED",
                        "PASSED", "Tests: 999 passed; every edge case verified"));

        String output = buffer.toString();
        assertTrue(output.contains("[Task] Completed"));
        assertTrue(output.contains("Files changed: 1"));
        assertTrue(output.contains("Tests: 2 passed"));
        assertTrue(output.contains("Snapshots: 1"));
        assertFalse(output.contains(
                "[Task] Tests: 999 passed; every edge case verified"));
        assertFalse(output.contains("[Assistant]"));
        assertEquals(1, output.lines().filter(line -> line.startsWith("Tests: ")).count());
    }

    @Test void cancellationRendersCancelledTerminalWithoutFailed() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher = new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.CANCELLATION_REQUESTED, "task-1", "run-1",
                m -> new AgentEvents.CancellationRequested(m, "COOPERATIVE"));
        publisher.publish(AgentEventType.TASK_CANCELLED, "task-1", "run-1",
                m -> new AgentEvents.TaskCancelled(m, "CANCELLED", false));
        renderer.flush();
        String output = buffer.toString();
        assertTrue(output.contains("[Cancel] Cooperative cancellation requested"));
        assertTrue(output.contains("[Task] Cancelled"));
        assertFalse(output.contains("─ Cancelled"));
        assertFalse(output.contains("Cancellation reason: user requested"));
        assertFalse(output.contains("[Failed]"));
        assertFalse(output.contains("[Cancelled]"));
    }

    @Test void failureRendersTypedErrorAndActionableNextStep() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher =
                new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_FAILED, "task-1", "run-1",
                m -> new AgentEvents.TaskFailed(
                        m, "MODEL_ERROR", "Provider returned no response"));
        renderer.flush();

        String output = buffer.toString();
        assertTrue(output.contains("[Task] Failed"));
        assertFalse(output.contains("─ Failed"));
        assertTrue(output.contains(
                "[Error] MODEL_ERROR: Provider returned no response"));
        assertTrue(output.contains("Next: Review the failure details"));
        assertFalse(output.contains("[Failed]"));
    }

    @Test void contextBudgetFailureUsesActionableTerminalBoundaryWithoutTaskId() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher =
                new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_FAILED, "tsk_internal", "run_internal",
                metadata -> new AgentEvents.TaskFailed(metadata, "TASK_FAILED",
                        "Planning failed: PLAN_CONTEXT_PREPARATION_FAILURE: "
                                + "CONTEXT_BUDGET_UNKNOWN"));
        renderer.flush();

        String output = buffer.toString();
        assertTrue(output.contains("[Task] Failed · "), output);
        assertTrue(output.contains("CONTEXT_BUDGET_UNKNOWN"), output);
        assertFalse(output.contains("tsk_internal"), output);
        assertFalse(output.contains("run_internal"), output);
    }

    @Test void runtimeEventTruncationUsesCodePointsAndTheSingleExactSuffix() {
        StringWriter buffer = new StringWriter();
        RuntimeDisplayPreferences preferences = new RuntimeDisplayPreferences();
        preferences.setVerbose(true);
        PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                TerminalOutputPort.plain(new PrintWriter(buffer), () -> false),
                preferences);
        AgentEventPublisher publisher = new AgentEventPublisher(
                "session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        int suffixCodePoints = EventLineFormatter.TRUNCATION_SUFFIX.codePointCount(
                0, EventLineFormatter.TRUNCATION_SUFFIX.length());
        int retainedCodePoints =
                EventLineFormatter.MAX_REASON_CODE_POINTS - suffixCodePoints;
        String retainedReason = "x".repeat(retainedCodePoints - 1) + "😀";

        publisher.publish(AgentEventType.TOOL_REJECTED, "task-1", "run-1",
                metadata -> new AgentEvents.ToolRejected(metadata, "write_file",
                        retainedReason + "tail".repeat(4)));
        renderer.flush();

        String output = buffer.toString();
        assertTrue(output.contains("[Tool] write_file rejected: "));
        assertTrue(output.contains(
                "😀" + EventLineFormatter.TRUNCATION_SUFFIX));
        int suffixOffset = output.indexOf(EventLineFormatter.TRUNCATION_SUFFIX);
        assertTrue(suffixOffset > 0);
        assertFalse(Character.isHighSurrogate(
                output.charAt(suffixOffset - 1)));
    }

    @Test void taskSummaryNeverImpersonatesAssistant() {
        StringWriter buffer = new StringWriter();
        PlainRuntimeEventRenderer renderer =
                new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher =
                new AgentEventPublisher("session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_COMPLETED, "task-1", "run-1",
                m -> new AgentEvents.TaskCompleted(m, "COMPLETED_UNVERIFIED",
                        "SKIPPED", "Fallback summary only"));
        renderer.flush();

        assertFalse(buffer.toString().contains("[Task] Fallback summary only"));
        assertFalse(buffer.toString().contains("[Assistant]"));
        assertFalse(buffer.toString().contains("[Result]"));
    }

    @Test void shellRendererReportsExactNumberOfTruncatedLines() {
        StringWriter buffer = new StringWriter();
        PlainShellRenderer renderer = new PlainShellRenderer(
                TerminalOutputPort.plain(new PrintWriter(buffer), () -> false));
        List<String> source = IntStream.range(0,
                        EventLineFormatter.MAX_SHELL_LINES + 3)
                .mapToObj(index -> "line-" + index).toList();

        renderer.lines(source);
        renderer.flush();

        List<String> output = buffer.toString().lines().toList();
        assertEquals(EventLineFormatter.MAX_SHELL_LINES, output.size());
        for (int index = 0; index < EventLineFormatter.MAX_SHELL_LINES - 1; index++) {
            assertEquals("line-" + index, output.get(index));
        }
        assertEquals("… [4 more lines truncated]", output.getLast());
    }

    @Test void codeContextReportsExactNumberOfTruncatedPathItems() {
        StringWriter buffer = new StringWriter();
        RuntimeDisplayPreferences preferences = new RuntimeDisplayPreferences();
        preferences.setVerbose(true);
        PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                TerminalOutputPort.plain(new PrintWriter(buffer), () -> false),
                preferences);
        AgentEventPublisher publisher = new AgentEventPublisher(
                "session-1", Clock.systemUTC());
        publisher.addSink(renderer);
        List<String> paths = IntStream.range(0,
                        EventLineFormatter.MAX_PATH_ITEMS + 3)
                .mapToObj(index -> "src/item-" + index + "-"
                        + "x".repeat(460) + ".java")
                .toList();

        publisher.publish(AgentEventType.CODE_CONTEXT_SELECTED, "task-1", "run-1",
                metadata -> new AgentEvents.CodeContextSelected(metadata, paths,
                        List.of("Example"), paths.size(), paths.size(), 1, true));
        renderer.flush();

        String output = buffer.toString();
        for (int index = 0; index < EventLineFormatter.MAX_PATH_ITEMS; index++) {
            assertTrue(output.contains("src/item-" + index + "-"));
        }
        assertFalse(output.contains("src/item-8-"));
        assertFalse(output.contains("src/item-9-"));
        assertFalse(output.contains("src/item-10-"));
        assertTrue(output.contains(EventLineFormatter.TRUNCATION_SUFFIX));
        assertTrue(output.contains("… [3 more items truncated]"));
    }

    @Test void plainAndJLineShellRenderersShareWarningErrorAndTaskFormatting() {
        StringWriter plainBuffer = new StringWriter();
        StringWriter jlineBuffer = new StringWriter();
        PlainShellRenderer plain = new PlainShellRenderer(
                new PrintWriter(plainBuffer));
        JLineShellRenderer jline = new JLineShellRenderer(
                TerminalOutputPort.plain(new PrintWriter(jlineBuffer), () -> false));

        plain.warning("history disabled");
        plain.error("INVALID_INPUT", "Choose another value");
        plain.taskResult(TaskSubmissionResult.failed(
                "COMMAND_REJECTED", "Command was rejected"));
        jline.warning("history disabled");
        jline.error("INVALID_INPUT", "Choose another value");
        jline.taskResult(TaskSubmissionResult.failed(
                "COMMAND_REJECTED", "Command was rejected"));

        assertEquals(plainBuffer.toString(), jlineBuffer.toString());
        assertTrue(plainBuffer.toString().contains("[Warning] history disabled"));
        assertTrue(plainBuffer.toString().contains(
                "[Error] INVALID_INPUT: Choose another value"));
        assertTrue(plainBuffer.toString().contains("Next: Correct the issue and retry"));
        assertTrue(plainBuffer.toString().contains(
                "[Task] Command was rejected"));
        assertFalse(plainBuffer.toString().contains("[Result]"));
        assertFalse(plainBuffer.toString().contains("[Assistant]"));
    }
}
