package com.paicli.interactive.render;

import com.paicli.event.AgentEventMetadata;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.task.TaskSubmissionResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForegroundSemanticRendererTest {
    @Test
    void preparingContainsNoAcceptedAcknowledgement() {
        assertEquals(List.of("[Task] Preparing..."),
                EventLineFormatter.taskResultLines(
                        TaskSubmissionResult.preparing()));
    }

    @Test
    void durableTaskStartedIsTheOnlySemanticAcceptedBoundary() {
        AgentEvents.TaskStarted event = new AgentEvents.TaskStarted(
                new AgentEventMetadata(AgentEventType.TASK_STARTED,
                        "session-1", "task-1", "run-1", 1, Instant.EPOCH),
                "inspect the workspace", "INITIAL");

        List<String> lines = EventLineFormatter.transcriptLines(
                EventLineFormatter.format(event));

        assertEquals("[Task] Task accepted.", lines.getFirst());
        assertEquals("[Task] Started: inspect the workspace", lines.get(1));
        assertFalse(String.join("\n", lines).contains("Task · Planning"));
        assertFalse(String.join("\n", lines).contains("task-1"));
        assertFalse(lines.getFirst().contains("Preparing"));
    }

    @Test
    void semanticStylesDifferentiateStatusAndPlainFormattingStaysAnsiFree()
            throws Exception {
        try (org.jline.terminal.Terminal terminal = org.jline.terminal.TerminalBuilder
                .builder().system(false)
                .streams(new java.io.ByteArrayInputStream(new byte[0]),
                        new java.io.ByteArrayOutputStream())
                .type("xterm-256color").build()) {
            String task = TerminalSemanticStyle.line("[Task] Preparing...", terminal);
            String warning = TerminalSemanticStyle.line("[Warning] caution", terminal);
            String error = TerminalSemanticStyle.line("[Error] FAILED: detail", terminal);
            assertTrue(task.contains("\u001b["));
            assertNotEquals(task, warning);
            assertNotEquals(warning, error);
        }
        java.io.StringWriter plain = new java.io.StringWriter();
        new PlainShellRenderer(new java.io.PrintWriter(plain))
                .lines(List.of("[Task] Preparing...", "[Warning] caution"));
        assertFalse(plain.toString().contains("\u001b["));
        assertFalse(TerminalSemanticStyle.input("user input", null)
                .toAnsi().contains("\u001b["));
    }
}
