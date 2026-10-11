package com.paicli.interactive.render;

import com.paicli.event.AgentEventMetadata;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.shell.InteractivePromptRenderer;
import com.paicli.interactive.view.InteractiveSessionViewModel;
import com.paicli.interactive.view.ProviderView;
import com.paicli.safety.ApprovalDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimplifiedPresentationTest {
    @Test
    void promptAndApprovalRemainStaticLineOrientedText() {
        InteractivePromptRenderer prompts = new InteractivePromptRenderer();
        assertEquals("paicli> ", prompts.primary());
        assertEquals("paicli [running: VERIFYING]> ", prompts.running("VERIFYING"));
        assertEquals("....> ", prompts.secondary());

        InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                "approval-1", "session-1", "task-1", "run-1", 1,
                "run_command", null, ApprovalDecision.RiskLevel.HIGH,
                "on-request", null, InteractiveApprovalRequest.Kind.COMMAND,
                "Command: mvn test", null, Instant.parse("2026-07-24T00:00:00Z"));
        String approval = prompts.approval(request);
        assertTrue(approval.startsWith("approval [command]>"));
        assertTrue(approval.contains("Run command? (y=yes, n=reject, d=diff, c=cancel):"));
        assertFalse(approval.contains(String.valueOf((char) 27)));
    }

    @Test
    void providerSummaryIsCompactWhileExistingDetailSurfaceRemainsAvailable() {
        ProviderView provider = new ProviderView(
                "ollama", "qwen3:14b", "session",
                ProviderView.AvailabilityState.AVAILABLE,
                ProviderView.ConfigurationState.CONFIGURED,
                ProviderView.ReadinessState.READINESS_UNKNOWN,
                ProviderView.LastCallState.LAST_CALL_SUCCEEDED,
                ProviderView.UsageState.REPORTED,
                "PLAN", "SUCCEEDED", null,
                20_300L, 485, 1_083, 1_568,
                1, 1,
                485L, 1_083L, 1_568L,
                485L, 1_083L, 1_568L,
                Instant.parse("2026-07-30T00:00:00Z"), "diagnostic-secret");
        InteractiveSessionViewModel view = new InteractiveSessionViewModel(
                "snapshot", Instant.parse("2026-07-30T00:00:01Z"), "session",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, provider, null, List.of());
        InteractiveViewModelRenderer renderer = new InteractiveViewModelRenderer();

        List<String> summary = renderer.provider(view, false);
        String compact = String.join("\n", summary);

        assertTrue(summary.size() <= 30, compact);
        assertTrue(summary.stream().allMatch(line ->
                line.codePointCount(0, line.length()) <= 80), compact);
        assertTrue(summary.getFirst().contains("Provider Status"), compact);
        assertTrue(summary.getLast().matches("─+"), compact);
        assertTrue(compact.contains("  Provider    Ollama"), compact);
        assertTrue(compact.contains("  Model       qwen3:14b"), compact);
        assertTrue(compact.contains("  Endpoint    Local"), compact);
        assertTrue(compact.contains("  Health      Not checked"), compact);
        assertTrue(compact.contains("  Last call   Plan · succeeded · 20.3s"), compact);
        assertTrue(compact.contains(
                "  Usage       485 input · 1,083 output · 1,568 total"), compact);
        assertTrue(compact.contains("  Calls       1 this session"), compact);
        assertFalse(compact.contains("Projection:"), compact);
        assertFalse(compact.contains("diagnostic-secret"), compact);

        String details = String.join("\n", renderer.provider(view, true));
        assertTrue(details.contains("Projection: AVAILABLE"), details);
        assertTrue(details.contains("Diagnostic reference: diagnostic-secret"), details);
        assertTrue(details.contains("Session calls: 1"), details);
    }

    @Test
    void deepSeekProviderSummaryShowsModeAndEffortButNeverReasoning() {
        ProviderView provider = new ProviderView(
                "openai", "deepseek-v4-flash", "project",
                ProviderView.AvailabilityState.AVAILABLE,
                ProviderView.ConfigurationState.CONFIGURED,
                ProviderView.ReadinessState.READINESS_UNKNOWN,
                ProviderView.LastCallState.UNKNOWN,
                ProviderView.UsageState.USAGE_NOT_EXPOSED,
                null,null,null,null,null,null,null,0,0,
                null,null,null,null,null,null,null,null,
                "enabled","max");
        InteractiveSessionViewModel view = new InteractiveSessionViewModel(
                "snapshot", Instant.parse("2026-07-30T00:00:01Z"), "session",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, provider, null, List.of());
        String compact=String.join("\n",new InteractiveViewModelRenderer().provider(view,false));
        assertTrue(compact.contains("  Thinking    Enabled"),compact);
        assertTrue(compact.contains("  Effort      max"),compact);
        assertFalse(compact.toLowerCase().contains("reasoning_content"),compact);
    }

    @Test
    void retainedHealthAndLatestCallRenderAsIndependentCompactFacts() {
        Instant observed=Instant.parse("2026-07-30T00:00:42Z");
        ProviderView provider=new ProviderView("openai","deepseek-v4-flash","project",
                ProviderView.AvailabilityState.AVAILABLE,
                ProviderView.ConfigurationState.CONFIGURED,
                ProviderView.ReadinessState.HEALTHY,
                ProviderView.LastCallState.LAST_CALL_SUCCEEDED,
                ProviderView.UsageState.REPORTED,"CHAT","FINAL",null,5_000L,
                100,50,150,2,1,100L,50L,150L,100L,50L,150L,
                observed,"ptc:safe","enabled","max",observed.minusSeconds(42));
        InteractiveSessionViewModel view=new InteractiveSessionViewModel("snapshot",observed,
                "session",null,null,null,null,null,null,null,null,null,null,null,null,null,
                null,null,null,provider,null,List.of());

        String compact=String.join("\n",new InteractiveViewModelRenderer().provider(view,false));
        assertTrue(compact.contains("  Health      Ready"),compact);
        assertTrue(compact.contains("  Checked     42s ago"),compact);
        assertTrue(compact.contains("  Freshness   Fresh"),compact);
        assertTrue(compact.contains("  Last call   Chat · succeeded · 5.0s"),compact);
        assertFalse(compact.contains("reasoning_content"),compact);
    }

    @Test
    void transcriptIsAppendOnlyAndAnsiFree() {
        AtomicBoolean composer = new AtomicBoolean(false);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        output.writeLine("status: " + (char) 27 + "[31mfailed"
                + (char) 27 + "[0m\nnext");

        assertEquals(List.of("status: failed", "next"), sink.appended);
        assertFalse(String.join("", sink.appended).contains(String.valueOf((char) 27)));
    }

    @Test
    void composerQueuesNormalAndCriticalEventsInOneFifoWithoutBypass() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        output.writeLine("normal-before");
        output.writeLine("[Approval] command", TerminalOutputPort.Priority.CRITICAL);
        output.writeLine("normal-after");

        assertTrue(sink.appended.isEmpty());

        composer.set(false);
        output.flush();
        assertEquals(List.of("normal-before", "[Approval] command", "normal-after"),
                sink.appended);
    }

    @Test
    void ansiFreeFallbackQueuesCriticalEventsWithoutLosingInformation() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        output.writeLine("[Approval] create file", TerminalOutputPort.Priority.CRITICAL);
        assertTrue(sink.appended.isEmpty());

        composer.set(false);
        output.flush();
        assertEquals(List.of("[Approval] create file"), sink.appended);
    }

    @Test
    void runtimeStatusAndApprovalStayInPublicationOrderWhileComposerIsActive() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);
        RuntimeDisplayPreferences preferences = new RuntimeDisplayPreferences();
        preferences.setVerbose(true);
        InteractiveRuntimeEventRenderer renderer =
                new InteractiveRuntimeEventRenderer(output, preferences);

        renderer.accept(new AgentEvents.PhaseChanged(
                metadata(AgentEventType.PHASE_CHANGED, 1), "VERIFYING"));
        renderer.accept(new AgentEvents.ApprovalRequested(
                metadata(AgentEventType.APPROVAL_REQUESTED, 2),
                "run_command", null, 0, 0, false,
                "COMMAND", "Command: mvn test", "high risk"));

        assertTrue(sink.appended.isEmpty());

        composer.set(false);
        renderer.flush();
        assertEquals("[Phase] VERIFYING", sink.appended.get(0));
        assertTrue(sink.appended.get(1).startsWith("[Proposed Command]"));
    }

    @Test
    void pendingEventLimitSuppressesNormalBlocksAndFlushReportsExactlyOnce() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        for (int index = 0;
                index < SerializedTerminalOutputPort.MAX_PENDING_EVENTS - 1;
                index++) {
            output.writeLine("event-" + index);
        }
        output.writeLines(List.of("block-first", "block-second"));
        output.writeLine("suppressed");

        composer.set(false);
        output.flush();
        int sizeAfterFirstFlush = sink.appended.size();
        output.flush();

        assertEquals(SerializedTerminalOutputPort.MAX_PENDING_EVENTS + 2,
                sizeAfterFirstFlush);
        assertEquals("block-first", sink.appended.get(sizeAfterFirstFlush - 3));
        assertEquals("block-second", sink.appended.get(sizeAfterFirstFlush - 2));
        assertEquals("[Output] 1 background updates were suppressed",
                sink.appended.get(sizeAfterFirstFlush - 1));
        assertEquals(sizeAfterFirstFlush, sink.appended.size());
    }

    @Test
    void pendingUtf8ByteLimitUsesEncodedBytesAndSuppressesOverflow() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);
        String multibyteLine = "界".repeat(4096);

        for (int index = 0; index < 22; index++) {
            output.writeLine(multibyteLine);
        }

        composer.set(false);
        output.flush();
        assertEquals(22, sink.appended.size());
        assertEquals("[Output] 1 background updates were suppressed",
                sink.appended.get(21));
    }

    @Test
    void criticalOverflowEvictsOldestNormalWithoutReorderingRetainedEvents() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        output.writeLine("critical-first", TerminalOutputPort.Priority.CRITICAL);
        output.writeLine("normal-to-evict");
        for (int index = 0;
                index < SerializedTerminalOutputPort.MAX_PENDING_EVENTS - 2;
                index++) {
            output.writeLine("critical-" + index,
                    TerminalOutputPort.Priority.CRITICAL);
        }
        output.writeLine("critical-last", TerminalOutputPort.Priority.CRITICAL);

        composer.set(false);
        output.flush();
        assertEquals("critical-first", sink.appended.get(0));
        assertFalse(sink.appended.contains("normal-to-evict"));
        assertEquals("critical-0", sink.appended.get(1));
        assertEquals("critical-last",
                sink.appended.get(SerializedTerminalOutputPort.MAX_PENDING_EVENTS - 1));
        assertEquals("[Output] 1 background updates were suppressed",
                sink.appended.get(SerializedTerminalOutputPort.MAX_PENDING_EVENTS));
    }

    @Test
    void criticalOnlyCapacityFailsClosedInsteadOfDroppingOutput() {
        AtomicBoolean composer = new AtomicBoolean(true);
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);

        for (int index = 0;
                index < SerializedTerminalOutputPort.MAX_PENDING_EVENTS;
                index++) {
            output.writeLine("critical-" + index,
                    TerminalOutputPort.Priority.CRITICAL);
        }
        output.writeLine("normal-cannot-evict-critical");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> output.writeLine("cannot-fit",
                        TerminalOutputPort.Priority.CRITICAL));
        assertTrue(failure.getMessage().contains("terminal output failure"));
        assertTrue(sink.appended.isEmpty());
    }

    @Test
    void writeLinesRemainsAtomicAgainstConcurrentOutput() throws Exception {
        AtomicBoolean composer = new AtomicBoolean(false);
        BlockingCaptureSink sink = new BlockingCaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(composer::get, sink);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread blockWriter = new Thread(() -> {
            try {
                output.writeLines(List.of("block-first", "block-second"));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        blockWriter.start();
        assertTrue(sink.firstLine.await(5, TimeUnit.SECONDS));

        Thread eventWriter = new Thread(() -> {
            sink.eventStarted.countDown();
            output.writeLine("async-event");
        });
        eventWriter.start();
        assertTrue(sink.eventStarted.await(5, TimeUnit.SECONDS));
        sink.releaseFirstLine.countDown();
        blockWriter.join(5000);
        eventWriter.join(5000);

        assertFalse(blockWriter.isAlive());
        assertFalse(eventWriter.isAlive());
        assertNull(failure.get());
        assertEquals(List.of("block-first", "block-second", "async-event"),
                sink.appended);
    }

    private static AgentEventMetadata metadata(AgentEventType type, long sequence) {
        return new AgentEventMetadata(type, "session-1", "task-1", "run-1",
                sequence, Instant.EPOCH);
    }

    private static final class CaptureSink
            implements SerializedTerminalOutputPort.Sink {
        private final List<String> appended = new ArrayList<>();

        @Override public void append(String line) { appended.add(line); }
        @Override public void flush() { }
    }

    private static final class BlockingCaptureSink
            implements SerializedTerminalOutputPort.Sink {
        private final List<String> appended = new ArrayList<>();
        private final CountDownLatch firstLine = new CountDownLatch(1);
        private final CountDownLatch releaseFirstLine = new CountDownLatch(1);
        private final CountDownLatch eventStarted = new CountDownLatch(1);

        @Override
        public void append(String line) {
            appended.add(line);
            if ("block-first".equals(line)) {
                firstLine.countDown();
                try {
                    assertTrue(releaseFirstLine.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
        }

        @Override public void flush() { }
    }
}
