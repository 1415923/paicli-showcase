package com.paicli.interactive.shell;

import com.paicli.config.Config;
import com.paicli.interactive.approval.ApprovalInteractionResult;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.command.SlashCommandRouter;
import com.paicli.interactive.completion.WorkspacePathGuard;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.input.InteractiveInputParser;
import com.paicli.interactive.render.InteractiveShellRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.ForegroundTask;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.safety.ApprovalDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForegroundCompatibilityShellTest {
    @TempDir Path temp;

    @Test
    void foregroundTaskSuspendsComposerStreamsOutputAndRestoresOnlyAfterTerminal()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("foreground"));
        ControlledPort port = new ControlledPort();
        RecordingReader reader = new RecordingReader("do work", "/status", "/exit");
        RecordingRenderer renderer = new RecordingRenderer();
        AtomicReference<InteractiveShellResult> result = new AtomicReference<>();

        Thread shell = Thread.ofPlatform().start(
                () -> result.set(shell(workspace, reader, renderer, port).run()));
        assertTrue(port.submitted.await(2, TimeUnit.SECONDS));
        assertEquals(1, reader.reads(),
                "the main composer must not be read while the Task is active");
        assertEquals(TaskSubmissionResult.Status.PREPARING,
                renderer.taskResults.getFirst().status());
        assertFalse(port.foreground().admission().isDone());

        port.admit("run-1");
        renderer.lines(List.of("[Phase] EXECUTING"));
        assertTrue(renderer.lines.contains("[Phase] EXECUTING"),
                "runtime output must be visible without a later Enter");
        assertEquals(1, reader.reads());

        port.finish(TaskSubmissionResult.completed("Task completed."));
        shell.join(3000);

        assertFalse(shell.isAlive());
        assertNotNull(result.get());
        assertEquals(1, result.get().submittedTasks());
        assertEquals(3, reader.reads());
        assertEquals(1, reader.owners.size());
        assertTrue(reader.prompts.get(1).contains("paicli"));
        assertTrue(reader.closed);
        assertNull(port.session.snapshot().currentTaskId());
    }

    @Test
    void approvalUsesTheSameForegroundInputOwnerAndNeverRestoresMainComposer()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("approval"));
        ControlledPort port = new ControlledPort();
        RecordingReader reader = new RecordingReader("do work", "y", "/exit");
        RecordingRenderer renderer = new RecordingRenderer();
        port.approvalOnAdmission = true;

        InteractiveShellResult result = shell(workspace, reader, renderer, port).run();

        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(3, reader.reads());
        assertEquals(1, reader.owners.size());
        assertTrue(reader.prompts.get(1).startsWith("approval ["));
        assertFalse(reader.prompts.get(1).contains("paicli [running"));
        assertTrue(reader.prompts.get(2).contains("paicli"));
        assertEquals(List.of("Approval recorded."), renderer.lines.stream()
                .filter("Approval recorded."::equals).toList());
        assertNull(port.session.snapshot().currentTaskId());
    }

    @Test
    void terminalDuringApprovalEndsTheSoleForegroundReadWithoutAnotherEnter()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("approval-terminal"));
        ControlledPort port = new ControlledPort();
        port.approvalOnAdmission = true;
        TerminalAwareReader reader = new TerminalAwareReader();
        RecordingRenderer renderer = new RecordingRenderer();
        AtomicReference<InteractiveShellResult> result = new AtomicReference<>();

        Thread shell = Thread.ofPlatform().start(
                () -> result.set(shell(workspace, reader, renderer, port).run()));
        assertTrue(reader.approvalStarted.await(2, TimeUnit.SECONDS));
        assertEquals(1, reader.mainReads.get(),
                "the idle composer must remain suspended during approval");

        port.expireApprovalAndFinish();
        shell.join(3000);

        assertFalse(shell.isAlive(),
                "terminal Task must invalidate the approval read without Enter");
        assertNotNull(result.get());
        assertEquals(2, reader.mainReads.get());
        assertEquals(0, port.approvalResponses.get());
        assertEquals(1, reader.owners.size());
        assertTrue(reader.approvalPrompt.startsWith("approval ["));
        assertNull(port.session.snapshot().currentTaskId());
    }

    @Test
    void escRequestsOneCooperativeCancellationAndRestoresReadyComposer()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("esc-cancel"));
        ControlledPort port = new ControlledPort();
        EscReader reader = new EscReader();
        RecordingRenderer renderer = new RecordingRenderer();

        InteractiveShellResult result = shell(workspace, reader, renderer, port).run();

        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(1, port.cancellationRequests.get());
        assertEquals(2, reader.mainReads.get());
        assertTrue(renderer.lines.stream().anyMatch(line -> line.contains(
                "Esc cancels the current task; Ctrl+C exits PaiCLI")));
        assertNull(port.session.snapshot().currentTaskId());
    }

    private InteractiveShell shell(Path workspace, ShellInputReader input,
            InteractiveShellRenderer renderer, TaskSubmissionPort port) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        return new InteractiveShell(
                new InteractiveShellConfig("1.2.0", workspace,
                        Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                        InteractiveSession.TerminalMode.PLAIN),
                input, renderer,
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.2.0", registry,
                        new SlashCommandParser(registry), () -> List.of()),
                port,
                new InteractiveHistoryStore(temp.resolve(
                        "history-" + System.nanoTime())),
                new InteractivePromptRenderer(), Clock.systemUTC());
    }

    private static final class ControlledPort implements TaskSubmissionPort {
        private final CountDownLatch submitted = new CountDownLatch(1);
        private final AtomicReference<ForegroundTask> active = new AtomicReference<>();
        private volatile InteractiveApprovalRequest approval;
        private volatile boolean approvalOnAdmission;
        private final AtomicInteger approvalResponses = new AtomicInteger();
        private final AtomicInteger cancellationRequests = new AtomicInteger();
        private InteractiveSession session;

        @Override
        public void openSession(InteractiveSession opened) {
            session = opened;
        }

        @Override
        public TaskSubmissionResult submit(InteractiveTaskRequest request) {
            CompletableFuture<ForegroundTask.DurableAdmission> admission =
                    new CompletableFuture<>();
            CompletableFuture<TaskSubmissionResult> terminal =
                    new CompletableFuture<>();
            ForegroundTask foreground = new ForegroundTask(
                    "task-1", admission, terminal);
            assertTrue(active.compareAndSet(null, foreground));
            session.startTask("task-1");
            submitted.countDown();
            if (approvalOnAdmission) admit("run-1");
            return TaskSubmissionResult.preparing();
        }

        @Override
        public Optional<ForegroundTask> activeForegroundTask() {
            return Optional.ofNullable(active.get());
        }

        @Override
        public Optional<InteractiveApprovalRequest> pendingApproval() {
            return Optional.ofNullable(approval);
        }

        @Override
        public ApprovalInteractionResult respondApproval(String requestId, String input) {
            approvalResponses.incrementAndGet();
            assertNotNull(approval);
            assertEquals(approval.requestId(), requestId);
            approval = null;
            finish(TaskSubmissionResult.completed("Task completed."));
            return new ApprovalInteractionResult(true, false,
                    List.of("Approval recorded."));
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            return active.get() == null;
        }

        @Override
        public void requestCancellation() {
            cancellationRequests.incrementAndGet();
            finish(TaskSubmissionResult.cancelled("Task cancelled."));
        }

        private ForegroundTask foreground() {
            return active.get();
        }

        private void admit(String runId) {
            ForegroundTask foreground = active.get();
            assertNotNull(foreground);
            session.bindRun(foreground.taskId(), runId);
            foreground.admission().complete(
                    new ForegroundTask.DurableAdmission(
                            foreground.taskId(), runId));
            if (approvalOnAdmission) {
                approval = new InteractiveApprovalRequest(
                        "approval-1", session.snapshot().sessionId(),
                        foreground.taskId(), runId, 1, "run_command", null,
                        ApprovalDecision.RiskLevel.HIGH, Config.MODE_ON_REQUEST,
                        null, InteractiveApprovalRequest.Kind.COMMAND,
                        "Command: echo safe", null,
                        Instant.now().plusSeconds(30));
            }
        }

        private void finish(TaskSubmissionResult terminal) {
            ForegroundTask foreground = active.getAndSet(null);
            assertNotNull(foreground);
            session.finishTask(foreground.taskId());
            foreground.terminal().complete(terminal);
        }

        private void expireApprovalAndFinish() {
            approval = null;
            finish(TaskSubmissionResult.completed("Task completed."));
        }
    }

    private static final class EscReader implements ShellInputReader {
        private final AtomicInteger mainReads = new AtomicInteger();
        private final AtomicInteger foregroundPolls = new AtomicInteger();

        @Override
        public String readLine(String prompt) {
            return mainReads.getAndIncrement() == 0 ? "do work" : "/exit";
        }

        @Override
        public ForegroundReadResult pollForegroundAction(PromptIdentity identity,
                java.util.function.BooleanSupplier ownershipValid) {
            return foregroundPolls.getAndIncrement() == 0
                    ? ForegroundReadResult.cancelTask()
                    : ForegroundReadResult.none();
        }
    }

    private static final class TerminalAwareReader implements ShellInputReader {
        private final CountDownLatch approvalStarted = new CountDownLatch(1);
        private final java.util.Set<Thread> owners =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicInteger mainReads = new AtomicInteger();
        private volatile String approvalPrompt;

        @Override
        public String readLine(String prompt) {
            owners.add(Thread.currentThread());
            return mainReads.getAndIncrement() == 0 ? "do work" : "/exit";
        }

        @Override
        public ForegroundReadResult readForegroundChoice(String prompt,
                PromptIdentity identity,
                java.util.function.BooleanSupplier ownershipValid) {
            owners.add(Thread.currentThread());
            approvalPrompt = prompt;
            approvalStarted.countDown();
            while (ownershipValid.getAsBoolean()) {
                try {
                    Thread.sleep(5L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return ForegroundReadResult.invalidated();
                }
            }
            return ForegroundReadResult.invalidated();
        }
    }

    private static final class RecordingReader implements ShellInputReader {
        private final List<String> values;
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private final java.util.Set<Thread> owners =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private int index;
        private volatile boolean closed;

        private RecordingReader(String... values) {
            this.values = List.of(values);
        }

        @Override
        public synchronized String readLine(String prompt) {
            owners.add(Thread.currentThread());
            prompts.add(prompt);
            return index < values.size() ? values.get(index++) : null;
        }

        private synchronized int reads() {
            return index;
        }

        @Override public void close() { closed = true; }
    }

    private static final class RecordingRenderer implements InteractiveShellRenderer {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<TaskSubmissionResult> taskResults =
                new CopyOnWriteArrayList<>();

        @Override public void startup(String version,
                InteractiveSession.Snapshot session) { }
        @Override public void lines(List<String> values) { lines.addAll(values); }
        @Override public void taskResult(TaskSubmissionResult result) {
            taskResults.add(result);
        }
        @Override public void inputCleared() { lines.add("Input cleared."); }
        @Override public void warning(String message) { lines.add(message); }
        @Override public void error(String code, String message) {
            lines.add(code + ": " + message);
        }
        @Override public void closed() { }
    }
}
