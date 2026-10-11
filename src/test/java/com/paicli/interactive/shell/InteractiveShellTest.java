package com.paicli.interactive.shell;

import com.paicli.config.Config;
import com.paicli.interactive.command.*;
import com.paicli.interactive.completion.WorkspacePathGuard;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.input.InteractiveInputParser;
import com.paicli.interactive.render.InteractiveShellRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.interactive.approval.ApprovalInteractionResult;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.query.InteractiveTaskView;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.InteractiveRuntimeTaskExecutor;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.safety.ApprovalDecision;
import com.paicli.state.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveShellTest {
    @TempDir Path temp;

    @Test void runsLocalCommandsAndClosesSession() throws Exception {
        Path workspace = workspace();
        FakeReader input = new FakeReader("", "/help", "/status", "/clear", "/exit");
        CaptureRenderer renderer = new CaptureRenderer();
        InteractiveShellResult result = shell(workspace, input, renderer,
                TaskSubmissionPort.runtimeNotConnected()).run();
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertTrue(renderer.lines.stream().anyMatch(value -> value.contains("Interactive commands")));
        assertTrue(renderer.lines.stream().anyMatch(value -> value.contains("Context generation: 2")));
        assertFalse(renderer.lines.stream().anyMatch(value -> value.contains("Active task:")));
        assertTrue(input.closed);
    }

    @Test void naturalLanguagePreparesBeforeAdmissionAndRestoresComposerAfterTerminal()
            throws Exception {
        Path workspace = workspace();
        List<InteractiveTaskRequest> requests = new CopyOnWriteArrayList<>();
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch beforeAdmission = new CountDownLatch(1);
        CountDownLatch releaseAdmission = new CountDownLatch(1);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch releaseTerminal = new CountDownLatch(1);
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            requests.add(request);
            state.tasks.createTask(taskId, request.input(), "ollama", "model",
                    "read-only", 1);
            state.tasks.markRunning(taskId);
            beforeAdmission.countDown();
            assertTrue(releaseAdmission.await(2, TimeUnit.SECONDS));
            bridge.taskStarted(taskId, "run-1", request.input());
            admitted.countDown();
            assertTrue(releaseTerminal.await(2, TimeUnit.SECONDS));
            state.tasks.markDone(taskId);
            return new CodingTaskResult(taskId, "run-1", null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
        };
        try (InteractiveRuntimeAdapter runtime = InteractiveRuntimeAdapter.open(
                workspace, executor, events::add)) {
            CaptureRenderer renderer = new CaptureRenderer();
            FakeReader reader = new FakeReader(
                    "inspect @src/Test.java\\", "then explain", "/exit");
            AtomicReference<InteractiveShellResult> result = new AtomicReference<>();
            Thread shell = Thread.ofPlatform().start(
                    () -> result.set(shell(workspace, reader, renderer, runtime).run()));

            assertTrue(beforeAdmission.await(2, TimeUnit.SECONDS));
            assertTrue(renderer.preparingRendered.await(2, TimeUnit.SECONDS));
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    renderer.taskResults.getFirst().status());
            assertTrue(renderer.taskResults.stream().noneMatch(item ->
                    item.status() == TaskSubmissionResult.Status.ACCEPTED));
            assertTrue(events.stream().noneMatch(event ->
                    event.metadata().eventType() == AgentEventType.TASK_STARTED));
            assertEquals(2, reader.index,
                    "the main composer must stop after multiline submission");

            releaseAdmission.countDown();
            assertTrue(admitted.await(2, TimeUnit.SECONDS));
            assertTrue(events.stream().anyMatch(event ->
                    event.metadata().eventType() == AgentEventType.TASK_STARTED));
            assertEquals(2, reader.index,
                    "durable admission must not restore the main composer");

            releaseTerminal.countDown();
            shell.join(3000);
            assertFalse(shell.isAlive());
            assertNotNull(result.get());
            assertEquals(1, result.get().submittedTasks());
            assertEquals(3, reader.index,
                    "the idle composer returns only after terminal convergence");
            assertEquals(List.of("src/Test.java"), requests.getFirst().fileReferences());
            assertTrue(requests.getFirst().input().contains("\n"));
            int started = eventIndex(events, AgentEventType.TASK_STARTED);
            int terminal = eventIndex(events, AgentEventType.TASK_COMPLETED);
            assertTrue(started >= 0 && terminal > started);
            assertEquals(1, renderer.taskOutputStarts);
            assertEquals(1, renderer.taskOutputEnds);
        }
    }

    @Test void configuredMockRejectsOneTaskWithoutSubmissionAndShellRemainsUsable() throws Exception {
        Path workspace = workspace();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        TaskSubmissionPort port = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                submissions.incrementAndGet();
                return TaskSubmissionResult.accepted("unexpected");
            }
            public TaskSubmissionResult admitOrdinaryInput(String input) {
                admissions.incrementAndGet();
                return TaskSubmissionResult.accepted("unexpected");
            }
        };
        CaptureRenderer renderer = new CaptureRenderer();
        SlashCommandRegistry registry = new SlashCommandRegistry();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("mock-history"));
        InteractiveShellResult result = new InteractiveShell(
                new InteractiveShellConfig("1.1.0", workspace, Config.PROVIDER_MOCK,
                        null, Config.MODE_ON_REQUEST, InteractiveSession.TerminalMode.PLAIN),
                new FakeReader("do work", "/help", "/exit"), renderer,
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.1.0", registry, new SlashCommandParser(registry),
                        () -> List.of()), port, history,
                new InteractivePromptRenderer(), Clock.systemUTC()).run();

        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(0, result.submittedTasks());
        assertEquals(0, admissions.get());
        assertEquals(0, submissions.get());
        assertEquals(1, renderer.errors.size());
        assertTrue(renderer.taskResults.isEmpty());
        assertTrue(renderer.errors.getFirst().startsWith("MOCK_PROVIDER_UNSUPPORTED:"));
        assertTrue(renderer.lines.stream().anyMatch(line -> line.contains("Interactive commands")));
        assertFalse(history.load().contains("do work"));
    }

    @Test void configuredMockRejectsExplicitContinueBeforeFollowUpPort() throws Exception {
        Path workspace = workspace();
        AtomicInteger followUps = new AtomicInteger();
        TaskSubmissionPort port = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.accepted("unexpected");
            }
            public TaskSubmissionResult submitFollowUp(
                    String input, boolean explicit, String expectedHeadToken) {
                followUps.incrementAndGet();
                return TaskSubmissionResult.accepted("unexpected");
            }
        };
        CaptureRenderer renderer = new CaptureRenderer();
        runMockShell(workspace, new FakeReader("/continue inspect next", "/help", "/exit"),
                renderer, port, InteractiveTaskCommandPort.unavailable());
        assertEquals(0, followUps.get());
        assertSingleMockAdmissionError(renderer);
        assertTrue(renderer.lines.stream().anyMatch(line -> line.contains("Interactive commands")));
    }

    @Test void configuredMockRejectsImplicitFollowUpBeforeFollowUpPort() throws Exception {
        Path workspace = workspace();
        AtomicInteger followUps = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        TaskSubmissionPort port = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.accepted("unexpected");
            }
            public TaskSubmissionResult admitOrdinaryInput(String input) {
                admissions.incrementAndGet();
                return new TaskSubmissionResult(TaskSubmissionResult.Status.ACCEPTED,
                        "FOLLOW_UP_INPUT", "eligible completed task");
            }
            public TaskSubmissionResult submitFollowUp(
                    String input, boolean explicit, String expectedHeadToken) {
                followUps.incrementAndGet();
                return TaskSubmissionResult.accepted("unexpected");
            }
        };
        CaptureRenderer renderer = new CaptureRenderer();
        InteractiveHistoryStore history = runMockShell(
                workspace, new FakeReader("inspect next", "/help", "/exit"), renderer,
                port, InteractiveTaskCommandPort.unavailable());
        assertEquals(0, admissions.get());
        assertEquals(0, followUps.get());
        assertSingleMockAdmissionError(renderer);
        assertFalse(history.load().contains("inspect next"));
        assertTrue(renderer.lines.stream().anyMatch(line -> line.contains("Interactive commands")));
    }

    @Test void configuredMockBlocksOnlyAffirmativeFollowUpConfirmations() throws Exception {
        for (var testCase : List.of(
                List.of("INTENT", "c"), List.of("INTENT", "new"),
                List.of("RISK", "yes"))) {
            Path workspace = workspace();
            AtomicInteger responses = new AtomicInteger();
            var request = followUpConfirmation(testCase.getFirst());
            TaskSubmissionPort port = oneShotConfirmationPort(request, responses);
            CaptureRenderer renderer = new CaptureRenderer();
            runMockShell(workspace,
                    new FakeReader(testCase.get(1), "/help", "/exit"), renderer,
                    port, InteractiveTaskCommandPort.unavailable());
            assertEquals(0, responses.get(), testCase.toString());
            assertSingleMockAdmissionError(renderer);
            assertTrue(renderer.lines.stream().anyMatch(
                    line -> line.contains("Interactive commands")));
        }
    }

    @Test void configuredMockAllowsNonStartingFollowUpConfirmationAnswersToPort() throws Exception {
        for (var testCase : List.of(
                List.of("INTENT", "cancel"), List.of("RISK", "no"),
                List.of("INTENT", "ambiguous"))) {
            Path workspace = workspace();
            AtomicInteger responses = new AtomicInteger();
            var request = followUpConfirmation(testCase.getFirst());
            TaskSubmissionPort port = oneShotConfirmationPort(request, responses);
            CaptureRenderer renderer = new CaptureRenderer();
            runMockShell(workspace, new FakeReader(testCase.get(1), "/exit"), renderer,
                    port, InteractiveTaskCommandPort.unavailable());
            assertEquals(1, responses.get(), testCase.toString());
            assertTrue(renderer.errors.isEmpty(), testCase.toString());
            assertEquals(1, renderer.taskResults.size(), testCase.toString());
        }
    }

    @Test void typedPendingPollFailureRendersOnceAndShellContinues() throws Exception {
        Path workspace = workspace();
        AtomicInteger polls = new AtomicInteger();
        TaskSubmissionPort port = new TaskSubmissionPort() {
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.accepted("unexpected");
            }
            public Optional<com.paicli.interactive.followup.FollowUpConfirmationRequest>
                    pendingFollowUpConfirmation() {
                if (polls.incrementAndGet() == 1) {
                    com.paicli.provider.ProviderFactory.requirePublicRuntimeProvider(
                            Config.PROVIDER_MOCK);
                }
                return Optional.empty();
            }
        };
        CaptureRenderer renderer = new CaptureRenderer();
        runMockShell(workspace, new FakeReader("/help", "/exit"), renderer,
                port, InteractiveTaskCommandPort.unavailable());
        assertSingleMockAdmissionError(renderer);
        assertTrue(renderer.lines.stream().anyMatch(
                line -> line.contains("Interactive commands")));
        assertTrue(polls.get() >= 2);
    }

    @Test void configuredMockRejectsTaskRecoveryBeforeRecoveryPort() throws Exception {
        Path workspace = workspace();
        AtomicInteger recoveries = new AtomicInteger();
        InteractiveTaskCommandPort tasks = new InteractiveTaskCommandPort() {
            public ShellCommandResult resume(List<String> arguments, InteractiveSession session) {
                return ShellCommandResult.continueWith("UNUSED", List.of());
            }
            public ShellCommandResult rewind(List<String> arguments, InteractiveSession session) {
                return ShellCommandResult.continueWith("UNUSED", List.of());
            }
            public ShellCommandResult recover(List<String> arguments, InteractiveSession session) {
                recoveries.incrementAndGet();
                return ShellCommandResult.continueWith("UNEXPECTED", List.of());
            }
        };
        CaptureRenderer renderer = new CaptureRenderer();
        runMockShell(workspace, new FakeReader("/task recover", "/exit"), renderer,
                TaskSubmissionPort.runtimeNotConnected(), tasks);
        assertEquals(0, recoveries.get());
        assertSingleMockAdmissionError(renderer);
    }

    @Test void unavailablePortUnknownCommandEofAndOversizeAreBounded() throws Exception {
        Path workspace = workspace();
        CaptureRenderer renderer = new CaptureRenderer();
        FakeReader input = new FakeReader("/stats", "x".repeat(8193),
                "do work", null);
        InteractiveShellResult result = shell(workspace, input, renderer,
                TaskSubmissionPort.runtimeNotConnected()).run();
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(0, renderer.cleared);
        assertTrue(renderer.errors.stream().anyMatch(value -> value.contains("did you mean /status")));
        assertTrue(renderer.errors.stream().anyMatch(value -> value.contains("8192")));
        assertEquals(List.of("PREPARING", "INTERACTIVE_PREVIEW_UNAVAILABLE"),
                renderer.taskResults.stream().map(TaskSubmissionResult::code).toList());
        assertEquals(0, result.submittedTasks());
    }

    @Test void interruptDuringMultilineInputExitsWithoutSubmittingPartialTask() throws Exception {
        Path workspace = workspace();
        AtomicInteger submissions = new AtomicInteger();
        CaptureRenderer renderer = new CaptureRenderer();
        TaskSubmissionPort port = request -> {
            submissions.incrementAndGet();
            return TaskSubmissionResult.accepted("unexpected");
        };
        InteractiveShellResult result = shell(workspace,
                new FakeReader("partial task\\", FakeReader.INTERRUPT, "/exit"),
                renderer, port).run();
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertEquals(0, submissions.get());
        assertEquals(0, renderer.cleared);
    }

    @Disabled("Deferred v1.3 active-composer contract; see terminal-architecture-v1.3.md")
    @Test void executingInterruptRequestsOneCancellationAndShellRemainsUsable()
            throws Exception {
        Path workspace = workspace();
        class RacingPort implements TaskSubmissionPort {
            InteractiveSession session;
            int cancellations;
            public void openSession(InteractiveSession value) {
                session = value;
                value.startTask("task-active", "run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public void requestCancellation() {
                cancellations++;
                if (session.snapshot().currentTaskId() != null) {
                    session.finishTask("task-active");
                }
            }
            public String currentPhase() { return "EXECUTING"; }
        }
        RacingPort port = new RacingPort();
        CaptureRenderer renderer = new CaptureRenderer();
        FakeReader reader = new FakeReader(FakeReader.INTERRUPT, "/status", "/exit");
        InteractiveShellResult result = shell(workspace, reader, renderer, port).run();
        assertEquals(1, port.cancellations);
        assertEquals(0, renderer.cleared);
        assertNull(port.session.snapshot().currentTaskId());
        assertTrue(renderer.lines.contains("Active task: none"));
        assertEquals(3, reader.index);
        assertEquals(1, reader.owners.size());
        assertTrue(reader.closed);
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
    }

    @Disabled("Deferred v1.3 active-composer contract; see terminal-architecture-v1.3.md")
    @Test void taskFailureDuringOwnedReadDoesNotExitTheSession() throws Exception {
        Path workspace = workspace();
        class FailedPort implements TaskSubmissionPort {
            InteractiveSession session;
            int cancellations;
            public void openSession(InteractiveSession value) {
                session = value;
                value.startTask("task-failed", "run-failed");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public void requestCancellation() { cancellations++; }
        }
        FailedPort port = new FailedPort();
        FakeReader reader = new FakeReader(
                () -> port.session.finishTask("task-failed"), "/status", "/exit");
        CaptureRenderer renderer = new CaptureRenderer();
        InteractiveShellResult result = shell(workspace, reader, renderer, port).run();
        assertEquals(0, port.cancellations);
        assertNull(port.session.snapshot().currentTaskId());
        assertTrue(renderer.lines.contains("Active task: none"));
        assertEquals(2, reader.index);
        assertEquals(1, reader.owners.size());
        assertTrue(reader.closed);
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
    }

    @Disabled("Replaced by v1.2 foreground terminal convergence tests; active /exit is unavailable")
    @Test void exitWaitsForWorkerBeforeRendererAndInputOwnersClose() throws Exception {
        Path workspace = workspace();
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch cancellation = new CountDownLatch(1);
        CountDownLatch workerStopped = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        ShellInputReader reader = new ShellInputReader() {
            public String readLine(String prompt) { return "/exit"; }
            public void close() { order.add("input-close"); }
        };
        InteractiveShellRenderer renderer = new CaptureRenderer() {
            @Override public void closed() { order.add("renderer-close"); }
        };
        class WorkerPort implements TaskSubmissionPort {
            InteractiveSession session;
            public void openSession(InteractiveSession value) {
                session = value;
                value.startTask("task-active", "run-active");
                worker.set(Thread.ofPlatform().start(() -> {
                    try {
                        cancellation.await();
                        value.finishTask("task-active");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    } finally {
                        order.add("worker-stop");
                        workerStopped.countDown();
                    }
                }));
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public void requestCancellation() { cancellation.countDown(); }
            public boolean awaitIdle(java.time.Duration timeout) {
                try {
                    return workerStopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            public void closeSession() {
                assertEquals(0, workerStopped.getCount());
                order.add("runtime-close");
            }
        }
        InteractiveShellResult result = shell(workspace, reader, renderer,
                new WorkerPort()).run();
        worker.get().join(2000);
        assertEquals(List.of("worker-stop", "runtime-close", "renderer-close", "input-close"),
                order);
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
    }

    @Test void isolatesRendererFailures() throws Exception {
        Path workspace = workspace();
        InteractiveShellRenderer broken = new InteractiveShellRenderer() {
            private void fail() { throw new IllegalStateException("renderer failure"); }
            public void startup(String version, InteractiveSession.Snapshot value) { fail(); }
            public void lines(List<String> value) { fail(); }
            public void taskResult(TaskSubmissionResult value) { fail(); }
            public void inputCleared() { fail(); }
            public void warning(String value) { fail(); }
            public void error(String code, String value) { fail(); }
            public void closed() { fail(); }
        };
        InteractiveShellResult result = shell(workspace, new FakeReader("/help", "/exit"), broken,
                TaskSubmissionPort.runtimeNotConnected()).run();
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
        assertTrue(result.rendererFailures() >= 3);
    }

    @Disabled("Replaced by v1.2 foreground approval tests; old background composer fixture")
    @Test void pendingApprovalUsesExplicitDefaultRejectPrompt() throws Exception {
        Path workspace = workspace();
        InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                "approval-1", "session", "task", "run", 1, "write_file",
                "src/Test.java", ApprovalDecision.RiskLevel.MEDIUM,
                Config.MODE_ON_REQUEST,
                new PreWriteDiff("src/Test.java", "a", "b", 1, 1,
                        List.of("-old", "+new"), false, false, true),
                Instant.now().plusSeconds(30));
        class ApprovalPort implements TaskSubmissionPort {
            boolean pending = true;
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("unexpected", "unexpected");
            }
            public java.util.Optional<InteractiveApprovalRequest> pendingApproval() {
                return pending ? java.util.Optional.of(request) : java.util.Optional.empty();
            }
            public ApprovalInteractionResult respondApproval(String requestId, String input) {
                assertEquals("approval-1", requestId);
                assertEquals("", input);
                pending = false;
                return new ApprovalInteractionResult(true, false, List.of("Edit rejected."));
            }
        }
        FakeReader input = new FakeReader("", "/exit");
        shell(workspace, input, new CaptureRenderer(), new ApprovalPort()).run();
        assertTrue(input.prompts.getFirst().startsWith("approval [edit Test.java]>"));
        assertTrue(input.prompts.getFirst().contains("[n] reject"));
        assertEquals(1, input.prompts.getFirst().split("Apply\\?", -1).length - 1);
        assertFalse(input.prompts.getFirst().contains("running: IDLE"));
    }

    @Disabled("Replaced by v1.2 foreground approval tests; old background composer fixture")
    @Test void cancelApprovalRoutesThroughTaskCancellationPort() throws Exception {
        Path workspace = workspace();
        InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                "approval-2", "session", "task", "run", 1, "write_file",
                "src/Test.java", ApprovalDecision.RiskLevel.MEDIUM,
                Config.MODE_ON_REQUEST, null, Instant.now().plusSeconds(30));
        class CancelPort implements TaskSubmissionPort {
            boolean pending = true; int cancellations;
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("unexpected", "unexpected");
            }
            public java.util.Optional<InteractiveApprovalRequest> pendingApproval() {
                return pending ? java.util.Optional.of(request) : java.util.Optional.empty();
            }
            public ApprovalInteractionResult respondApproval(String requestId, String input) {
                pending = false;
                cancellations++;
                return new ApprovalInteractionResult(true, true,
                        List.of("Task cancellation requested."));
            }
            public void requestCancellation() { cancellations++; }
        }
        CancelPort port = new CancelPort();
        shell(workspace, new FakeReader("c", "/exit"),
                new CaptureRenderer(), port).run();
        assertEquals(1, port.cancellations);
    }

    @Disabled("Ctrl+C is process termination in v1.2; cooperative in-session interrupt is deferred")
    @Test void approvalInterruptCancelsThroughTheExistingInputOwnerAndContinues()
            throws Exception {
        Path workspace = workspace();
        InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                "approval-interrupt", "session", "task-active", "run-active", 1,
                "write_file", "src/Test.java", ApprovalDecision.RiskLevel.HIGH,
                Config.MODE_ON_REQUEST, null, Instant.now().plusSeconds(30));
        class ApprovalInterruptPort implements TaskSubmissionPort {
            InteractiveSession session;
            boolean pending = true;
            int cancellations;
            public void openSession(InteractiveSession value) {
                session = value;
                value.startTask("task-active", "run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public Optional<InteractiveApprovalRequest> pendingApproval() {
                return pending ? Optional.of(request) : Optional.empty();
            }
            public void requestCancellation() {
                cancellations++;
                pending = false;
                if (session.snapshot().currentTaskId() != null) {
                    session.finishTask("task-active");
                }
            }
        }
        ApprovalInterruptPort port = new ApprovalInterruptPort();
        CaptureRenderer renderer = new CaptureRenderer();
        FakeReader reader = new FakeReader(FakeReader.INTERRUPT, "/status", "/exit");
        InteractiveShellResult result = shell(workspace, reader, renderer, port).run();
        assertEquals(1, port.cancellations);
        assertFalse(port.pending);
        assertNull(port.session.snapshot().currentTaskId());
        assertTrue(renderer.lines.contains("Active task: none"));
        assertEquals(3, reader.index);
        assertEquals(1, reader.owners.size());
        assertTrue(reader.closed);
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
    }

    @Disabled("Replaced by v1.2 foreground approval tests; old background composer fixture")
    @Test void approvalYesAndNoAreNeverSubmittedAsTasksOrPersistedToHistory() throws Exception {
        for (String decision : List.of("y", "n")) {
            Path workspace = workspace();
            InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                    "approval-" + decision, "session", "task", "run", 1,
                    "write_file", "src/Test.java", ApprovalDecision.RiskLevel.HIGH,
                    Config.MODE_ON_REQUEST, null, Instant.now().plusSeconds(30));
            AtomicInteger submissions = new AtomicInteger();
            class ApprovalPort implements TaskSubmissionPort {
                boolean pending = true;
                public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                    submissions.incrementAndGet();
                    return TaskSubmissionResult.accepted("unexpected");
                }
                public Optional<InteractiveApprovalRequest> pendingApproval() {
                    return pending ? Optional.of(request) : Optional.empty();
                }
                public ApprovalInteractionResult respondApproval(String id, String input) {
                    assertEquals(request.requestId(), id);
                    assertEquals(decision, input);
                    pending = false;
                    return new ApprovalInteractionResult(true, false, List.of("resolved"));
                }
            }
            Path historyPath = temp.resolve("approval-history-" + decision);
            InteractiveHistoryStore history = new InteractiveHistoryStore(historyPath);
            SlashCommandRegistry registry = new SlashCommandRegistry();
            FakeReader approvalInput = new FakeReader(decision, "/exit");
            new InteractiveShell(new InteractiveShellConfig("1.1.0", workspace,
                    Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                    InteractiveSession.TerminalMode.PLAIN),
                    approvalInput, new CaptureRenderer(),
                    new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                    new SlashCommandRouter("1.1.0", registry,
                            new SlashCommandParser(registry), () -> List.of()),
                    new ApprovalPort(), history, new InteractivePromptRenderer(),
                    Clock.systemUTC()).run();
            assertEquals(0, submissions.get());
            assertFalse(history.load().contains(decision));
            assertEquals("paicli> ", approvalInput.prompts.getLast());
        }
    }

    @Disabled("Deferred v1.3 running-task composer and Steering contract")
    @Test void naturalLanguageWhileTaskActiveEntersSteeringWithoutSecondTaskSubmission()
            throws Exception {
        Path workspace = workspace();
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger steerings = new AtomicInteger();
        class ActivePort implements TaskSubmissionPort {
            InteractiveSession session;
            public void openSession(InteractiveSession value) {
                session = value;
                value.startTask("task-active", "run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                submissions.incrementAndGet();
                return TaskSubmissionResult.accepted("unexpected");
            }
            public TaskSubmissionResult submitSteering(String input,boolean explicit){
                assertEquals("second task",input);assertFalse(explicit);steerings.incrementAndGet();
                return TaskSubmissionResult.accepted("steering queued");
            }
            public void requestCancellation() {
                if (session.snapshot().currentTaskId() != null) {
                    session.finishTask("task-active");
                }
            }
            public String currentPhase() { return "EXECUTING"; }
        }
        ActivePort port = new ActivePort();
        CaptureRenderer renderer = new CaptureRenderer();
        FakeReader reader = new FakeReader("second task", "/exit");
        shell(workspace, reader, renderer, port).run();
        assertEquals(0, submissions.get());
        assertEquals(1, steerings.get());
        assertTrue(renderer.taskResults.stream()
                .anyMatch(result -> "ACCEPTED".equals(result.code())));
        assertTrue(reader.prompts.getFirst().contains("running: EXECUTING"));
    }

    @Disabled("v1.2 accepts /exit only at idle; active-task /quit input is unavailable")
    @Test void quitCancelsActiveTaskBeforeExitingAsExitAlias() throws Exception {
        Path workspace = workspace();
        class ActivePort implements TaskSubmissionPort {
            InteractiveSession session; int cancellations;
            public void openSession(InteractiveSession value) {
                session = value; value.startTask("task-active", "run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public void requestCancellation() {
                cancellations++;
                if (session.snapshot().currentTaskId() != null) session.finishTask("task-active");
            }
        }
        ActivePort port = new ActivePort();
        InteractiveShellResult result = shell(workspace, new FakeReader("/quit"),
                new CaptureRenderer(), port).run();
        assertEquals(1, port.cancellations);
        assertEquals(InteractiveSession.Status.CLOSED, result.status());
    }

    @Test void historyClearAlsoUsesNarrowCurrentNavigationHistoryInterface()
            throws Exception {
        Path workspace = workspace();
        FakeReader reader = new FakeReader("ordinary input", "/history clear", "/exit");
        shell(workspace, reader, new CaptureRenderer(),
                request -> TaskSubmissionResult.accepted("accepted")).run();
        assertEquals(1, reader.navigationClears);
    }

    @Disabled("Deferred v1.3 running-task Steering; v1.2 foreground approval has sole input")
    @Test void approvalOwnedReadCannotBeRepurposedAsExplicitSteering()throws Exception{
        Path workspace=workspace();AtomicInteger approvalAnswers=new AtomicInteger();AtomicInteger steerings=new AtomicInteger();
        class Port implements TaskSubmissionPort{
            InteractiveSession session;InteractiveApprovalRequest pending;
            public void openSession(InteractiveSession value){session=value;value.startTask("task-active","run-active");pending=request();}
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<InteractiveApprovalRequest> pendingApproval(){return Optional.ofNullable(pending);}
            public ApprovalInteractionResult respondApproval(String id,String input){approvalAnswers.incrementAndGet();pending=null;return new ApprovalInteractionResult(true,false,List.of());}
            public TaskSubmissionResult submitSteering(String text,boolean explicit){assertEquals("format: concise",text);assertTrue(explicit);steerings.incrementAndGet();pending=null;return TaskSubmissionResult.accepted("queued");}
            public void requestCancellation(){if(session.snapshot().currentTaskId()!=null)session.finishTask("task-active");}
        }
        Port port=new Port();shell(workspace,new FakeReader("/steer format: concise","/exit"),new CaptureRenderer(),port).run();
        assertEquals(0,steerings.get());assertEquals(1,approvalAnswers.get());
    }

    @Disabled("Replaced by terminal-aware v1.2 foreground approval invalidation test")
    @Test void approvalInvalidatedDuringReadReturnsExpiredAndNeverAdmitsSteering()throws Exception{
        Path workspace=workspace();AtomicInteger approvalAnswers=new AtomicInteger();
        AtomicInteger steerings=new AtomicInteger();CaptureRenderer renderer=new CaptureRenderer();
        class Port implements TaskSubmissionPort{
            InteractiveApprovalRequest pending=request();
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){
                return TaskSubmissionResult.rejected("UNUSED","unused");
            }
            public Optional<InteractiveApprovalRequest> pendingApproval(){
                return Optional.ofNullable(pending);
            }
            public ApprovalInteractionResult respondApproval(String id,String input){
                approvalAnswers.incrementAndGet();
                return new ApprovalInteractionResult(false,false,List.of("unexpected"));
            }
            public TaskSubmissionResult submitSteering(String input,boolean explicit){
                steerings.incrementAndGet();return TaskSubmissionResult.accepted("unexpected");
            }
        }
        Port port=new Port();
        ShellInputReader reader=new ShellInputReader(){
            int reads;
            public String readLine(String prompt){
                if(reads++==0){port.pending=null;return "/steer format: concise";}
                return "/exit";
            }
        };
        shell(workspace,reader,renderer,port).run();
        assertEquals(0,approvalAnswers.get());
        assertEquals(0,steerings.get());
        assertTrue(renderer.lines.stream().anyMatch(line->
                line.contains("expired or was invalidated")));
    }

    @Disabled("Deferred v1.3 running-task status/Steering input")
    @Test void steeringStatusTextDuringApprovalStillAnswersOnlyOriginalApproval()throws Exception{
        Path workspace=workspace();AtomicInteger approvalAnswers=new AtomicInteger();AtomicInteger statuses=new AtomicInteger();
        class Port implements TaskSubmissionPort{
            InteractiveSession session;InteractiveApprovalRequest pending;
            public void openSession(InteractiveSession value){session=value;value.startTask("task-active","run-active");pending=request();}
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<InteractiveApprovalRequest> pendingApproval(){return Optional.ofNullable(pending);}
            public ApprovalInteractionResult respondApproval(String id,String input){approvalAnswers.incrementAndGet();pending=null;return new ApprovalInteractionResult(true,false,List.of());}
            public TaskSubmissionResult steeringStatus(){statuses.incrementAndGet();return TaskSubmissionResult.accepted("status");}
            public void requestCancellation(){if(session.snapshot().currentTaskId()!=null)session.finishTask("task-active");}
        }
        Port port=new Port();shell(workspace,new FakeReader("/steer status","/exit"),new CaptureRenderer(),port).run();
        assertEquals(0,statuses.get());assertEquals(1,approvalAnswers.get());
    }

    @Disabled("Deferred v1.3 running-task Steering history contract")
    @Test void allSteeringHistoryIsCanonicalSanitizedBeforePersistence()throws Exception{
        Path workspace=workspace();
        Path historyPath=temp.resolve("sanitized-steering-history");
        InteractiveHistoryStore history=new InteractiveHistoryStore(historyPath);
        AtomicInteger steerings=new AtomicInteger();
        class Port implements TaskSubmissionPort{
            InteractiveSession session;
            public void openSession(InteractiveSession value){
                session=value;value.startTask("task-active","run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){
                return TaskSubmissionResult.rejected("UNUSED","unused");
            }
            public TaskSubmissionResult submitSteering(String text,boolean explicit){
                steerings.incrementAndGet();return TaskSubmissionResult.accepted("queued");
            }
            public void requestCancellation(){
                if(session.snapshot().currentTaskId()!=null)session.finishTask("task-active");
            }
            public String currentPhase(){return "EXECUTING";}
        }
        String explicit="/steer priority: Authorization: Bearer raw-bearer-secret";
        String active="priority: token=raw-token password=raw-password api_key=raw-key "
                +"x".repeat(5000)+"\u0001";
        Port port=new Port();
        SlashCommandRegistry registry=new SlashCommandRegistry();
        new InteractiveShell(new InteractiveShellConfig("1.1.0",workspace,
                Config.PROVIDER_UNCONFIGURED,null,Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN),
                new FakeReader(explicit,active,"/exit"),new CaptureRenderer(),
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.1.0",registry,new SlashCommandParser(registry),
                        ()->List.of()),port,history,new InteractivePromptRenderer(),
                Clock.systemUTC()).run();
        String persisted=String.join("\n",history.load());
        assertEquals(2,steerings.get());
        assertFalse(persisted.contains("raw-bearer-secret"));
        assertFalse(persisted.contains("raw-token"));
        assertFalse(persisted.contains("raw-password"));
        assertFalse(persisted.contains("raw-key"));
        assertFalse(persisted.contains("\u0001"));
        assertTrue(persisted.contains("<redacted>"));
        assertTrue(history.load().stream().allMatch(value->value.length()<=4104));
    }

    @Test void explicitAndNaturalFollowUpShareRoutingAndSanitizeHistoryBeforePersistence()throws Exception{
        Path workspace=workspace();Path historyPath=temp.resolve("follow-up-history");
        InteractiveHistoryStore history=new InteractiveHistoryStore(historyPath);List<String> followUps=new ArrayList<>();
        TaskSubmissionPort port=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public TaskSubmissionResult admitOrdinaryInput(String input){return new TaskSubmissionResult(
                    TaskSubmissionResult.Status.ACCEPTED,"FOLLOW_UP_INPUT","terminal follow-up");}
            public Optional<String> currentFollowUpHeadToken(){return Optional.of("tsk_0123456789abcdef0123456789abcdef|7");}
            public TaskSubmissionResult submitFollowUp(String input,boolean explicit,String token){
                followUps.add((explicit?"E:":"N:")+token+":"+input);
                return TaskSubmissionResult.accepted("candidate");}
        };
        SlashCommandRegistry registry=new SlashCommandRegistry();
        new InteractiveShell(new InteractiveShellConfig("1.2.0",workspace,Config.PROVIDER_UNCONFIGURED,null,
                Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN),
                new FakeReader("/continue token=raw-explicit-secret","continue password=raw-natural-secret","/exit"),
                new CaptureRenderer(),new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.2.0",registry,new SlashCommandParser(registry),()->List.of()),
                port,history,new InteractivePromptRenderer(),Clock.systemUTC()).run();
        assertEquals(2,followUps.size());assertTrue(followUps.getFirst().startsWith("E:"));assertTrue(followUps.get(1).startsWith("N:"));
        String persisted=String.join("\n",history.load());assertFalse(persisted.contains("raw-explicit-secret"));
        assertFalse(persisted.contains("raw-natural-secret"));assertTrue(persisted.contains("<redacted>"));
    }

    @Test void allParsedContinueFormsUseFrozenHeadAndSanitizeBeforeHistory()throws Exception{
        Path workspace=workspace();InteractiveHistoryStore history=new InteractiveHistoryStore(temp.resolve("continue-equivalent"));
        List<String> calls=new ArrayList<>();TaskSubmissionPort port=new TaskSubmissionPort(){
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored){return TaskSubmissionResult.rejected("UNUSED","unused");}
            public Optional<String> currentFollowUpHeadToken(){return Optional.of("tsk_0123456789abcdef0123456789abcdef|9");}
            public TaskSubmissionResult submitFollowUp(String input,boolean explicit,String token){calls.add(token+"|"+input);
                return TaskSubmissionResult.accepted("candidate");}
        };SlashCommandRegistry registry=new SlashCommandRegistry();
        new InteractiveShell(new InteractiveShellConfig("1.2.0",workspace,Config.PROVIDER_UNCONFIGURED,null,
                Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN),
                new FakeReader("/continue token=raw-one","/CONTINUE password=raw-two",
                        "/continue\tapi_key=raw-three","/continue    Authorization: Bearer raw-four","/exit"),
                new CaptureRenderer(),new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.2.0",registry,new SlashCommandParser(registry),()->List.of()),
                port,history,new InteractivePromptRenderer(),Clock.systemUTC()).run();
        assertEquals(4,calls.size());assertTrue(calls.stream().allMatch(value->value.startsWith(
                "tsk_0123456789abcdef0123456789abcdef|9|")));
        String persisted=String.join("\n",history.load());
        for(String secret:List.of("raw-one","raw-two","raw-three","raw-four"))assertFalse(persisted.contains(secret));
        assertTrue(history.load().stream().filter(value->value.startsWith("/continue ")).count()==4);
    }

    private static InteractiveApprovalRequest request(){
        return new InteractiveApprovalRequest("approval-steer","session","task-active","run-active",1,
                "write_file","src/A.java",ApprovalDecision.RiskLevel.HIGH,Config.MODE_ON_REQUEST,
                new PreWriteDiff("src/A.java","a".repeat(64),"b".repeat(64),1,1,List.of("+x"),false,false,true),
                Instant.now().plusSeconds(30));
    }

    @Disabled("Deferred v1.3 retained composer and synthetic prompt-refresh contract")
    @Test void asynchronousTerminalHandoffRefreshesIdlePromptWithoutEnterOrSecondReader()
            throws Exception {
        Path workspace = workspace();
        class BlockingReader implements ShellInputReader {
            final CountDownLatch reading = new CountDownLatch(1);
            final ArrayBlockingQueue<String> values = new ArrayBlockingQueue<>(1);
            final List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
            final List<String> refreshes = new java.util.concurrent.CopyOnWriteArrayList<>();
            final AtomicInteger readers = new AtomicInteger();
            public String readLine(String prompt) {
                readers.incrementAndGet(); prompts.add(prompt); reading.countDown();
                try { return values.poll(5, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return null; }
            }
            public void runtimeStateChanged(String prompt) { refreshes.add(prompt); }
        }
        class TerminalPort implements TaskSubmissionPort {
            InteractiveSession session; Runnable terminal;
            public void openSession(InteractiveSession value) {
                session = value; value.startTask("task-active", "run-active");
            }
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.rejected("UNUSED", "unused");
            }
            public String currentPhase() { return "EXECUTING"; }
            public void onTerminal(Runnable listener) { terminal = listener; }
        }
        BlockingReader reader = new BlockingReader();
        TerminalPort port = new TerminalPort();
        InteractiveShell shell = shell(workspace, reader, new CaptureRenderer(), port);
        Thread shellThread = Thread.ofPlatform().start(shell::run);
        assertTrue(reader.reading.await(2, TimeUnit.SECONDS));
        assertTrue(reader.prompts.getFirst().contains("running: EXECUTING"));

        port.session.finishTask("task-active");
        port.terminal.run();
        assertEquals(List.of("paicli> "), reader.refreshes);
        reader.values.put("/exit");
        shellThread.join(3000);

        assertFalse(shellThread.isAlive());
        assertEquals(1, reader.refreshes.size());
        assertEquals(1, reader.readers.get());
    }

    @Test void startupDoesNotUseLegacyTaskViewsAsSavedSessions() throws Exception {
        Path workspace = workspace();
        CaptureRenderer renderer = new CaptureRenderer();
        InteractiveQueryFacade queries = new InteractiveQueryFacade() {
            public List<String> status(InteractiveSession.Snapshot session) { return List.of(); }
            public List<String> plan(InteractiveSession.Snapshot session) { return List.of(); }
            public List<String> tasks(int limit) { return List.of(); }
            public List<String> report(InteractiveSession.Snapshot session, String taskId) { return List.of(); }
            public List<InteractiveTaskView> taskViews(String filter, int limit) {
                return List.of(new InteractiveTaskView("task-1", "task-1", "now", "goal",
                        "ollama", "coder", "FAILED", "VERIFYING", true, 1, "FAILED"));
            }
        };
        SlashCommandRegistry registry = new SlashCommandRegistry();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("hint-history"));
        AtomicResumePort port = new AtomicResumePort();
        new InteractiveShell(new InteractiveShellConfig("1.1.0", workspace,
                Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN), new FakeReader("/exit"), renderer,
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.1.0", registry, new SlashCommandParser(registry),
                        () -> List.of(), queries), port, history,
                new InteractivePromptRenderer(), Clock.systemUTC()).run();
        assertFalse(renderer.lines.contains(
                "Saved Sessions are available. Use /sessions, then /resume <thread>."));
        assertEquals(0, port.resumes);
    }

    private Path workspace() throws Exception {
        Path workspace = temp.resolve("workspace-" + System.nanoTime());
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Test.java"), "class Test {}\n");
        return workspace;
    }

    private InteractiveHistoryStore runMockShell(Path workspace, ShellInputReader input,
            CaptureRenderer renderer, TaskSubmissionPort port,
            InteractiveTaskCommandPort taskCommands) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        InteractiveHistoryStore history = new InteractiveHistoryStore(
                temp.resolve("mock-path-history-" + System.nanoTime()));
        new InteractiveShell(new InteractiveShellConfig("1.1.0", workspace,
                Config.PROVIDER_MOCK, null, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN), input, renderer,
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.1.0", registry, new SlashCommandParser(registry),
                        () -> List.of(), InteractiveQueryFacade.unavailable(),
                        com.paicli.interactive.provider.InteractiveProviderCommandPort.unavailable(),
                taskCommands), port, history,
                new InteractivePromptRenderer(), Clock.systemUTC()).run();
        return history;
    }

    private static com.paicli.interactive.followup.FollowUpConfirmationRequest
            followUpConfirmation(String kind) {
        return new com.paicli.interactive.followup.FollowUpConfirmationRequest(
                com.paicli.identity.CanonicalId.newId(
                        com.paicli.identity.EntityFamily.CAPSULE).value(),
                kind, 1,
                com.paicli.identity.CanonicalId.newId(
                        com.paicli.identity.EntityFamily.TASK).value(), "preview");
    }

    private static TaskSubmissionPort oneShotConfirmationPort(
            com.paicli.interactive.followup.FollowUpConfirmationRequest request,
            AtomicInteger responses) {
        return new TaskSubmissionPort() {
            boolean offered;
            public TaskSubmissionResult submit(InteractiveTaskRequest ignored) {
                return TaskSubmissionResult.accepted("unexpected");
            }
            public Optional<com.paicli.interactive.followup.FollowUpConfirmationRequest>
                    pendingFollowUpConfirmation() {
                if (offered) return Optional.empty();
                offered = true;
                return Optional.of(request);
            }
            public TaskSubmissionResult respondFollowUpConfirmation(
                    String capsuleId, String kind, long version, String input) {
                responses.incrementAndGet();
                return TaskSubmissionResult.rejected(
                        "CONFIRMATION_RECORDED", "confirmation recorded");
            }
        };
    }

    private static void assertSingleMockAdmissionError(CaptureRenderer renderer) {
        assertEquals(1, renderer.errors.size());
        assertTrue(renderer.errors.getFirst().startsWith("MOCK_PROVIDER_UNSUPPORTED:"));
        assertTrue(renderer.taskResults.isEmpty());
    }

    private static int eventIndex(List<AgentEvent> events, AgentEventType type) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).metadata().eventType() == type) return index;
        }
        return -1;
    }

    private InteractiveShell shell(Path workspace, ShellInputReader input,
            InteractiveShellRenderer renderer, TaskSubmissionPort port) {
        SlashCommandRegistry registry = new SlashCommandRegistry();
        InteractiveHistoryStore history = new InteractiveHistoryStore(temp.resolve("history-" + System.nanoTime()));
        return new InteractiveShell(new InteractiveShellConfig("1.1.0", workspace,
                Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN), input, renderer,
                new InteractiveInputParser(new WorkspacePathGuard(workspace)),
                new SlashCommandRouter("1.1.0", registry, new SlashCommandParser(registry),
                        () -> List.of("Overall: WARN")),
                port, history, new InteractivePromptRenderer(), Clock.systemUTC());
    }

    private static final class FakeReader implements ShellInputReader {
        static final Object INTERRUPT = new Object();
        private final List<Object> values; private int index; private boolean closed;
        private final Runnable beforeFirstRead;
        private final java.util.Set<Thread> owners =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private int navigationClears;
        private final List<String> prompts = new ArrayList<>();
        FakeReader(Object... values) {
            this.values = java.util.Arrays.asList(values);
            this.beforeFirstRead = () -> { };
        }
        FakeReader(Runnable beforeFirstRead, Object... values) {
            this.values = java.util.Arrays.asList(values);
            this.beforeFirstRead = beforeFirstRead;
        }
        public String readLine(String prompt) throws InputInterruptedException {
            owners.add(Thread.currentThread());
            prompts.add(prompt);
            if (index == 0) beforeFirstRead.run();
            if (index >= values.size()) return null;
            Object value = values.get(index++);
            if (value == INTERRUPT) throw new InputInterruptedException();
            return (String) value;
        }
        public void close() { closed = true; }
        public boolean clearNavigationHistory() { navigationClears++; return true; }
    }

    private static class CaptureRenderer implements InteractiveShellRenderer {
        final List<String> lines = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final List<TaskSubmissionResult> taskResults = new ArrayList<>();
        final CountDownLatch preparingRendered = new CountDownLatch(1);
        int cleared;
        int taskOutputStarts;
        int taskOutputEnds;
        public void startup(String version, InteractiveSession.Snapshot value) { }
        public void lines(List<String> value) { lines.addAll(value); }
        public void beginTaskOutput() { taskOutputStarts++; }
        public void endTaskOutput() { taskOutputEnds++; }
        public void taskResult(TaskSubmissionResult value) {
            taskResults.add(value);
            if (value.status() == TaskSubmissionResult.Status.PREPARING) {
                preparingRendered.countDown();
            }
        }
        public void inputCleared() { cleared++; }
        public void warning(String value) { lines.add(value); }
        public void error(String code, String value) { errors.add(code + ":" + value); }
        public void closed() { }
    }

    private static final class AtomicResumePort implements TaskSubmissionPort {
        int resumes;
        public TaskSubmissionResult submit(InteractiveTaskRequest request) {
            return TaskSubmissionResult.rejected("UNUSED", "unused");
        }
    }
}
