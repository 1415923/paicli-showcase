package com.paicli.interactive.runtime;

import com.paicli.config.ConfigLoader;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.identity.CanonicalId;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.shell.InteractivePromptRenderer;
import com.paicli.interactive.task.ForegroundTask;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.state.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ForegroundAdmissionBoundaryTest {
    @TempDir Path temp;

    @Test
    void localSubmissionRemainsPreparingUntilTaskStartedBindsRun() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("admission"));
        CountDownLatch beforeAdmissionSignal = new CountDownLatch(1);
        CountDownLatch releaseAdmissionSignal = new CountDownLatch(1);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            state.tasks.createTask(taskId, request.input(),
                    "ollama", "model", "read-only", 1);
            state.tasks.markRunning(taskId);
            beforeAdmissionSignal.countDown();
            assertTrue(releaseAdmissionSignal.await(2, TimeUnit.SECONDS));
            bridge.taskStarted(taskId, "run-1", request.input());
            state.tasks.markDone(taskId);
            return new CodingTaskResult(taskId, "run-1", null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
        };

        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, events::add)) {
            InteractiveSession session = new InteractiveSession(
                    "session-1", workspace, "ollama", "model", "read-only",
                    InteractiveSession.TerminalMode.PLAIN, Instant.now());
            session.start();
            adapter.openSession(session);

            TaskSubmissionResult submission = adapter.submit(
                    new InteractiveTaskRequest("session-1", workspace,
                            "inspect", List.of()));
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    submission.status());
            assertTrue(beforeAdmissionSignal.await(2, TimeUnit.SECONDS));
            ForegroundTask foreground =
                    adapter.activeForegroundTask().orElseThrow();
            assertFalse(foreground.admission().isDone());

            releaseAdmissionSignal.countDown();
            ForegroundTask.DurableAdmission admitted =
                    foreground.admission().get(2, TimeUnit.SECONDS);
            assertEquals("run-1", admitted.runId());
            foreground.terminal().get(2, TimeUnit.SECONDS);
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(2)));
            assertTrue(events.stream().anyMatch(event ->
                    event.metadata().eventType() == AgentEventType.TASK_STARTED
                            && "run-1".equals(event.metadata().runId())));
            assertTrue(adapter.activeForegroundTask().isEmpty());
            assertNull(session.snapshot().currentTaskId());
        }
    }

    @Test
    void unexpectedAdmittedFailureTerminalizesTaskRunAndAllowsNextSubmission()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("failure-convergence"));
        AtomicInteger attempts = new AtomicInteger();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            int attempt = attempts.incrementAndGet();
            String runId = "run-failure-" + attempt;
            state.tasks.createTask(taskId, request.input(),
                    "ollama", "model", "read-only", 1);
            state.codingRuns.create(runId, taskId,
                    com.paicli.state.CodingRunStore.KIND_INITIAL, "", 1, 0);
            state.tasks.markRunning(taskId);
            bridge.taskStarted(taskId, runId, request.input());
            if (attempt == 1) throw new IllegalStateException(
                    "PROVIDER_TIMEOUT: bounded failure");
            state.codingRuns.markCompleted(runId);
            state.tasks.markDone(taskId);
            return new CodingTaskResult(taskId, runId, null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
        };

        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, event -> { })) {
            InteractiveSession session = new InteractiveSession(
                    "session-failure", workspace, "ollama", "model", "read-only",
                    InteractiveSession.TerminalMode.PLAIN, Instant.now());
            session.start();
            adapter.openSession(session);

            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(new InteractiveTaskRequest(
                            session.snapshot().sessionId(), workspace,
                            "first", List.of())).status());
            ForegroundTask first = adapter.activeForegroundTask().orElseThrow();
            ForegroundTask.DurableAdmission firstAdmission =
                    first.admission().get(2, TimeUnit.SECONDS);
            assertEquals(TaskSubmissionResult.Status.FAILED,
                    first.terminal().get(2, TimeUnit.SECONDS).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(2)));
            assertNull(session.snapshot().currentTaskId());
            assertEquals("IDLE", adapter.view().snapshot().phase());
            try (com.paicli.state.StateStore state =
                         new com.paicli.state.StateStore(workspace)) {
                assertEquals(TaskStore.STATUS_FAILED, state.tasks
                        .findById(firstAdmission.taskId()).orElseThrow().status());
                assertEquals(com.paicli.state.CodingRunStore.PHASE_FAILED,
                        state.codingRuns.findById(firstAdmission.runId())
                                .orElseThrow().phase());
            }

            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(new InteractiveTaskRequest(
                            session.snapshot().sessionId(), workspace,
                            "second", List.of())).status());
            ForegroundTask second = adapter.activeForegroundTask().orElseThrow();
            assertEquals(TaskSubmissionResult.Status.COMPLETED,
                    second.terminal().get(2, TimeUnit.SECONDS).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(2)));
            assertNull(session.snapshot().currentTaskId());
        }
    }

    @Test
    void realContextPreparationFailureTerminalizesBothDurableAuthoritiesAndRestoresReady()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("context-budget-failure"));
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/config.json"), """
                {"provider":"ollama","model":"unknown-test-model",
                 "approval_mode":"read-only","enable_verify":false,
                 "enable_repair":false,"enable_skills":false,
                 "enable_hooks":false,"max_iterations":2}
                """);
        Path isolatedHome = Files.createDirectories(temp.resolve("isolated-home"));
        AtomicInteger providerCalls = new AtomicInteger();
        ProductionInteractiveTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, () -> new ConfigLoader(workspace, isolatedHome).load(),
                ignored -> (messages, tools) -> {
                    providerCalls.incrementAndGet();
                    return com.paicli.model.ModelResponse.finalAnswer("must not be called");
                });

        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, event -> { })) {
            InteractiveSession session = new InteractiveSession(
                    "session-context-budget", workspace, "ollama",
                    "unknown-test-model", "read-only",
                    InteractiveSession.TerminalMode.PLAIN, Instant.now());
            session.start();
            adapter.openSession(session);

            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(new InteractiveTaskRequest(
                            session.snapshot().sessionId(), workspace,
                            "Summarize this small workspace without changing files.",
                            List.of())).status());
            ForegroundTask foreground = adapter.activeForegroundTask().orElseThrow();
            ForegroundTask.DurableAdmission admitted =
                    foreground.admission().get(5, TimeUnit.SECONDS);
            TaskSubmissionResult terminal = foreground.terminal()
                    .get(5, TimeUnit.SECONDS);

            assertEquals(TaskSubmissionResult.Status.FAILED, terminal.status());
            assertTrue(terminal.message().contains("CONTEXT_BUDGET_UNKNOWN"),
                    terminal.message());
            assertEquals(0, providerCalls.get());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(2)));
            assertNull(session.snapshot().currentTaskId());
            assertEquals("IDLE", adapter.view().snapshot().phase());

            try (com.paicli.state.StateStore state =
                         new com.paicli.state.StateStore(workspace)) {
                assertEquals(TaskStore.STATUS_FAILED, state.tasks
                        .findById(admitted.taskId()).orElseThrow().status());
                assertEquals(com.paicli.state.CodingRunStore.PHASE_FAILED,
                        state.codingRuns.findById(admitted.runId()).orElseThrow().phase());
                assertEquals("FAILED", state.r1aDurable
                        .findTask(CanonicalId.parse(admitted.taskId()))
                        .orElseThrow().status());
                assertEquals("FAILED", state.r1aDurable
                        .findRun(CanonicalId.parse(admitted.runId()))
                        .orElseThrow().terminalStatus());
            }

            StateStoreInteractiveQueryFacade query =
                    new StateStoreInteractiveQueryFacade(workspace, adapter.view());
            var model = query.readViewModel(session.snapshot()).value();
            assertEquals(com.paicli.interactive.view.OperationalStatusView.Status.READY,
                    model.operationalStatus().primary());
            assertEquals("paicli [ready]> ",
                    new InteractivePromptRenderer(query).primary(session.snapshot()));
        }
    }
}
