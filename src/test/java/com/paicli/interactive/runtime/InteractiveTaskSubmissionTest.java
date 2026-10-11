package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.render.PlainRuntimeEventRenderer;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.TaskExecutionAuthority;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.state.CodingRunStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.FollowUpModels;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.provider.ProviderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveTaskSubmissionTest {
    @TempDir Path temp;

    @Test void enforcesSingleActiveTaskAndAllowsSequentialTasks() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            int call = executions.incrementAndGet();
            state.tasks.createTask(taskId, request.input(), "mock", null, "read-only", 1);
            state.tasks.markRunning(taskId);
            String runId = "run-" + call;
            bridge.taskStarted(taskId, runId, request.input());
            release.await(5, TimeUnit.SECONDS);
            state.tasks.markDone(taskId);
            CodingTaskResult result = new CodingTaskResult(taskId, runId, null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
            bridge.taskCompleted(result);
            return result;
        };
        List<AgentEvent> events = new ArrayList<>();
        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, events::add)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            InteractiveTaskRequest request = request(session, workspace, "first");
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request).status());
            assertEquals("INTERACTIVE_TASK_ACTIVE",
                    adapter.submit(request(session, workspace, "second")).code());
            release.countDown();
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(TaskSubmissionResult.Status.COMPLETED,
                    adapter.lastResult().orElseThrow().status());
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "second")).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(2, executions.get());
        }
    }

    @Test void runtimeFailureBecomesTypedFailureAndSessionRemainsUsable() throws Exception {
        Path workspace = temp.resolve("failure");
        Files.createDirectories(workspace);
        AtomicInteger calls = new AtomicInteger();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("No production provider is configured.");
            }
            state.tasks.createTask(taskId, request.input(), "mock", null, "read-only", 1);
            state.tasks.markRunning(taskId);
            bridge.taskStarted(taskId, "run-ok", request.input());
            state.tasks.markDone(taskId);
            CodingTaskResult result = new CodingTaskResult(taskId, "run-ok", null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
            bridge.taskCompleted(result);
            return result;
        };
        List<AgentEvent> events = new ArrayList<>();
        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, events::add)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "first")).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(TaskSubmissionResult.Status.FAILED,
                    adapter.lastResult().orElseThrow().status());
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "second")).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(TaskSubmissionResult.Status.COMPLETED,
                    adapter.lastResult().orElseThrow().status());
        }
    }

    @Test void firstCancellationInterruptsCurrentOperationAndProducesOneTerminalOutcome() throws Exception {
        Path workspace = temp.resolve("cancel");
        Files.createDirectories(workspace);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            started.countDown();
            while (!bridge.cancellation().cancellationRequested()) {
                try { Thread.sleep(10); }
                catch (InterruptedException error) {
                    interrupted.set(true);
                    throw error;
                }
            }
            bridge.cancellation().throwIfCancellationRequested();
            throw new AssertionError("unreachable");
        };
        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, event -> { })) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "cancel")).status());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            adapter.requestCancellation();
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertTrue(interrupted.get());
            assertEquals(TaskSubmissionResult.Status.CANCELLED,
                    adapter.lastResult().orElseThrow().status());
            assertNull(session.snapshot().currentTaskId());
        }
    }

    @Test void cooperativeCancellationDoesNotInventTerminalBeforeWorkerConverges() throws Exception {
        Path workspace = temp.resolve("forced-cancel");
        Files.createDirectories(workspace);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            started.countDown();
            while (release.getCount() > 0) {
                try { Thread.sleep(100); }
                catch (InterruptedException ignored) { }
            }
            bridge.cancellation().throwIfCancellationRequested();
            throw new AssertionError("unreachable");
        };
        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, event -> { })) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "cancel")).status());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            adapter.requestCancellation();
            assertFalse(adapter.awaitIdle(Duration.ofMillis(100)));
            release.countDown();
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(TaskSubmissionResult.Status.CANCELLED,
                    adapter.lastResult().orElseThrow().status());
            assertEquals("CANCELLED",
                    adapter.lastResult().orElseThrow().code());
        }
    }

    @Test void admittedChildRunningResultIsInconclusiveWithoutLosingChildAuthority()
            throws Exception {
        Path workspace = temp.resolve("child-inconclusive");
        Files.createDirectories(workspace);
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        Config config = config(workspace);
        StateStore state = new StateStore(workspace);
        var parent = new InstructionManifestRuntime(state, workspace).admitRoot(
                CanonicalId.newId(EntityFamily.TASK), "parent", 10_000,
                config, RuntimeManifest.disabled(), at);
        markParentTerminal(workspace, parent.taskId(), parent.initialRun().id(), at);
        FollowUpService.Outcome admitted = new FollowUpService(state,
                new InstructionManifestRuntime(state, workspace), config,
                RuntimeManifest.disabled(), 10_000).begin(parent.threadId(),
                "continue child", true, "owner", "session", at.plusSeconds(1));
        assertNotNull(admitted.child());
        AtomicReference<String> executedTask = new AtomicReference<>();
        AtomicReference<String> executedRun = new AtomicReference<>();
        AtomicReference<FollowUpModels.ChildStartClaim> executedClaim =
                new AtomicReference<>();
        InteractiveRuntimeTaskExecutor executor = new InteractiveRuntimeTaskExecutor() {
            @Override public CodingTaskResult execute(String taskId,
                    InteractiveTaskRequest request, StateStore store,
                    RuntimeEventBridge bridge) {
                throw new AssertionError("root execution is unused");
            }

            @Override public CodingTaskResult executeAdmittedChild(String taskId,
                    String goal, StateStore store, RuntimeEventBridge bridge,
                    FollowUpModels.ChildStartClaim claim) {
                executedTask.set(taskId);
                executedRun.set(claim.child().runId().value());
                executedClaim.set(claim);
                bridge.taskStarted(taskId, claim.child().runId().value(), goal);
                return new CodingTaskResult(taskId, claim.child().runId().value(),
                        null, TaskStore.STATUS_RUNNING, null, 0,
                        "child execution remains authoritative and non-terminal");
            }
        };
        List<AgentEvent> events = new ArrayList<>();
        try (InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, executor, events::add, new InteractiveRuntimeView(), state)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            new SavedSessionService(state, workspace,
                    Clock.fixed(at.plusSeconds(2), ZoneOffset.UTC))
                    .bind(session, parent.threadId().value());
            String headToken = state.followUps.currentHeadToken(parent.threadId())
                    .orElseThrow();

            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submitFollowUp("recover admitted child", true,
                            headToken).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));

            assertEquals(admitted.child().taskId().value(), executedTask.get());
            assertEquals(admitted.child().runId().value(), executedRun.get());
            assertEquals(admitted.child(), executedClaim.get().child());
            assertEquals(TaskSubmissionResult.Status.INCOMPLETE,
                    adapter.lastResult().orElseThrow().status());
            assertEquals("FOLLOW_UP_INCOMPLETE",
                    adapter.lastResult().orElseThrow().code());
            assertEquals(admitted.child().taskId().value(),
                    adapter.lastResult().orElseThrow().taskId());
            assertEquals(admitted.child().runId().value(),
                    adapter.lastResult().orElseThrow().runId());
            assertEquals(TaskStore.STATUS_RUNNING, state.tasks
                    .findById(admitted.child().taskId().value()).orElseThrow().status());
            var codingRun = state.codingRuns
                    .findById(admitted.child().runId().value()).orElseThrow();
            assertEquals(CodingRunStore.KIND_INITIAL, codingRun.runKind());
            assertEquals(CodingRunStore.PHASE_PLANNING, codingRun.phase());
            assertEquals(admitted.child().taskId(), state.r1aDurable
                    .findRun(admitted.child().runId()).orElseThrow().taskId());
            assertNull(state.r1aDurable.findRun(admitted.child().runId())
                    .orElseThrow().terminalStatus());
            assertEquals("STARTED", queryText(workspace,
                    "SELECT state FROM child_initial_run_launches WHERE child_task_id='"
                            + admitted.child().taskId().value() + "'"));
            assertTerminalMutualExclusion(events);
        }
    }

    @Test void mockPendingPollRejectsRecoverableChildOnceWithoutAuthorityMutation()
            throws Exception {
        Path workspace = temp.resolve("mock-pending-child");
        Files.createDirectories(workspace);
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        Config config = config(workspace);
        StateStore state = new StateStore(workspace);
        var parent = new InstructionManifestRuntime(state, workspace).admitRoot(
                CanonicalId.newId(EntityFamily.TASK), "parent", 10_000,
                config, RuntimeManifest.disabled(), at);
        markParentTerminal(workspace, parent.taskId(), parent.initialRun().id(), at);
        FollowUpService.Outcome admitted = new FollowUpService(state,
                new InstructionManifestRuntime(state, workspace), config,
                RuntimeManifest.disabled(), 10_000).begin(parent.threadId(),
                "continue child", true, "owner", "session", at.plusSeconds(1));
        assertNotNull(admitted.child());
        AtomicInteger suppliers = new AtomicInteger();
        AtomicInteger resolvers = new AtomicInteger();
        Config supplied = config.withProvider(Config.PROVIDER_OLLAMA,
                "supplier-model", "http://localhost:11434", null);
        InteractiveRuntimeTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, () -> { suppliers.incrementAndGet(); return supplied; }, ignored -> {
                    resolvers.incrementAndGet();
                    throw new AssertionError("mock admission must stop before provider resolution");
                });
        String taskStatus = state.tasks.findById(admitted.child().taskId().value())
                .orElseThrow().status();
        String runPhase = state.codingRuns.findById(admitted.child().runId().value())
                .orElseThrow().phase();
        String launch = queryText(workspace,
                "SELECT state || ':' || launch_version FROM child_initial_run_launches"
                        + " WHERE child_task_id='" + admitted.child().taskId().value() + "'");
        try (InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, executor, event -> { }, new InteractiveRuntimeView(), state)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            assertEquals(Config.PROVIDER_MOCK,session.snapshot().provider());
            new SavedSessionService(state, workspace,
                    Clock.fixed(at.plusSeconds(2), ZoneOffset.UTC))
                    .bind(session, parent.threadId().value());

            ProviderFactory.UnsupportedRuntimeProviderException error = assertThrows(
                    ProviderFactory.UnsupportedRuntimeProviderException.class,
                    adapter::pendingFollowUpConfirmation);
            assertEquals(ProviderFactory.MOCK_UNSUPPORTED_CODE, error.code());
            assertTrue(adapter.pendingFollowUpConfirmation().isEmpty());

            assertEquals(0, suppliers.get());
            assertEquals(0, resolvers.get());
            assertTrue(adapter.activeHandle().isEmpty());
            assertNull(session.snapshot().currentTaskId());
            assertEquals(taskStatus, state.tasks.findById(
                    admitted.child().taskId().value()).orElseThrow().status());
            assertEquals(runPhase, state.codingRuns.findById(
                    admitted.child().runId().value()).orElseThrow().phase());
            assertEquals(launch, queryText(workspace,
                    "SELECT state || ':' || launch_version FROM child_initial_run_launches"
                            + " WHERE child_task_id='" + admitted.child().taskId().value() + "'"));
        }
    }

    @Test void supportedPendingPollStartsRecoverableChildExactlyOnce() throws Exception {
        Path workspace = temp.resolve("supported-pending-child");
        Files.createDirectories(workspace);
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        Config parentConfig = config(workspace);
        assertFalse(parentConfig.isPlanEnabled());
        StateStore state = new StateStore(workspace);
        var parent = new InstructionManifestRuntime(state, workspace).admitRoot(
                CanonicalId.newId(EntityFamily.TASK), "parent", 10_000,
                parentConfig, RuntimeManifest.disabled(), at);
        markParentTerminal(workspace, parent.taskId(), parent.initialRun().id(), at);
        assertEquals(TaskStore.STATUS_DONE,
                state.tasks.findById(parent.taskId().value()).orElseThrow().status());
        assertEquals(CodingRunStore.PHASE_COMPLETED, state.codingRuns
                .findById(parent.initialRun().id().value()).orElseThrow().phase());
        assertEquals("COMPLETED", state.r1aDurable.findRun(
                parent.initialRun().id()).orElseThrow().terminalStatus());
        Config childConfig = new Config(parentConfig.workspace(),
                parentConfig.commandTimeoutSeconds(), parentConfig.maxOutputBytes(),
                parentConfig.maxWriteFileBytes(), parentConfig.approvalMode(),
                parentConfig.maxProjectInstructionChars(), parentConfig.provider(),
                parentConfig.model(), parentConfig.baseUrl(), parentConfig.apiKeyEnv(),
                parentConfig.systemPrompt(), parentConfig.maxIterations(),
                parentConfig.verificationCommand(), parentConfig.verificationTimeoutSeconds(),
                parentConfig.maxVerificationOutputBytes(), parentConfig.maxRepairAttempts(), true,
                parentConfig.enableVerify(), parentConfig.enableRepair(),
                parentConfig.enableSkills(), parentConfig.skillsDirs(),
                parentConfig.maxSkillContextChars(), parentConfig.maxSelectedSkills(),
                parentConfig.enableHooks(), parentConfig.hooksConfigPath(),
                parentConfig.hookTimeoutSeconds(), parentConfig.maxHookOutputBytes(),
                parentConfig.hookFailurePolicy(), 131_072).withProvider(Config.PROVIDER_OLLAMA,
                "durable-model", "http://localhost:11434", null);
        assertTrue(childConfig.isPlanEnabled());
        assertEquals(131_072, childConfig.effectiveContextWindowTokens().intValue());
        FollowUpService.Outcome admitted = new FollowUpService(state,
                new InstructionManifestRuntime(state, workspace), childConfig,
                RuntimeManifest.disabled(), 10_000).begin(parent.threadId(),
                "continue child", true, "owner", "session", at.plusSeconds(1));
        assertNotNull(admitted.child());
        var durableChild = state.tasks.findById(
                admitted.child().taskId().value()).orElseThrow();
        assertEquals(Config.PROVIDER_OLLAMA, durableChild.provider());
        assertEquals("durable-model", durableChild.model());
        AtomicInteger suppliers = new AtomicInteger();
        AtomicInteger resolvers = new AtomicInteger();
        AtomicInteger delegateCalls = new AtomicInteger();
        AtomicReference<Config> resolved = new AtomicReference<>();
        Config supplied = childConfig.withModel("supplier-model");
        ConcurrentLinkedQueue<ModelResponse> responses = new ConcurrentLinkedQueue<>();
        responses.add(ModelResponse.finalAnswer(
                "{\"acceptanceCriteria\":[\"workspace inspected\"],\"assumptions\":[],"
                        + "\"risks\":[],\"verificationStrategy\":\"inspect tool evidence\","
                        + "\"steps\":[{\"title\":\"Inspect\",\"definition\":"
                        + "\"Inspect the workspace without mutation\",\"dependsOn\":[],"
                        + "\"completionRule\":\"ALL_REQUIRED_EVIDENCE\","
                        + "\"requiredEvidenceKinds\":[\"TOOL_CALL\"],"
                        + "\"effectClass\":\"READ\",\"toolRequirements\":[{"
                        + "\"requirementId\":\"list-workspace\",\"requirementOrder\":0,"
                        + "\"toolName\":\"list_files\",\"effectClass\":\"READ\","
                        + "\"authoritySelector\":{\"kind\":\"EXACT_PATH\",\"path\":\".\","
                        + "\"includeInternal\":false},\"minSuccesses\":1,"
                        + "\"maxOccurrences\":1,\"freshnessPolicy\":\"RESUME_REUSE_ONLY\"}]}]}"));
        responses.add(ModelResponse.toolCallResponse("inspect", new ToolCall(
                "list_files", Map.of("path", "."))));
        responses.add(ModelResponse.finalAnswer(
                "The workspace was inspected from durable read-only evidence."));
        InteractiveRuntimeTaskExecutor executor = new ProductionInteractiveTaskExecutor(
                workspace, () -> { suppliers.incrementAndGet(); return supplied; }, snapshot -> {
                    resolvers.incrementAndGet();
                    resolved.set(snapshot);
                    return (messages, tools) -> {
                        delegateCalls.incrementAndGet();
                        ModelResponse response = responses.poll();
                        if(response==null)throw new IllegalStateException("provider script exhausted");
                        return response;
                    };
                });
        List<AgentEvent> events = new ArrayList<>();
        try (InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, executor, events::add, new InteractiveRuntimeView(), state)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            new SavedSessionService(state, workspace,
                    Clock.fixed(at.plusSeconds(2), ZoneOffset.UTC))
                    .bind(session, parent.threadId().value());

            assertTrue(adapter.pendingFollowUpConfirmation().isEmpty());
            assertTrue(adapter.pendingFollowUpConfirmation().isEmpty());
            long approvalDeadline = System.nanoTime()
                    + Duration.ofSeconds(5).toNanos();
            while (adapter.pendingApproval().isEmpty()
                    && System.nanoTime() < approvalDeadline) {
                Thread.sleep(10);
            }
            var pendingApproval = adapter.pendingApproval();
            assertTrue(pendingApproval.isPresent());
            var planApproval = pendingApproval.orElseThrow();
            assertEquals(com.paicli.interactive.approval.InteractiveApprovalRequest.Kind.PLAN,
                    planApproval.kind());
            assertEquals("advisory_plan_confirmation", planApproval.toolName());
            assertEquals(admitted.child().taskId().value(), planApproval.taskId());
            assertEquals(admitted.child().runId().value(), planApproval.runId());
            assertNotNull(planApproval.requestId());
            assertFalse(planApproval.requestId().isBlank());
            assertEquals(session.snapshot().sessionId(), planApproval.sessionId());
            AtomicInteger approvalResponses = new AtomicInteger();
            approvalResponses.incrementAndGet();
            var approvalResult = adapter.respondApproval(
                    planApproval.requestId(), "apply");
            assertEquals(1, approvalResponses.get());
            assertTrue(approvalResult.resolved());
            assertFalse(approvalResult.cancelTask());
            assertTrue(adapter.pendingApproval().isEmpty());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertTrue(adapter.pendingFollowUpConfirmation().isEmpty());
            assertEquals(1, suppliers.get());
            assertEquals(1, resolvers.get());
            assertEquals(3, delegateCalls.get());
            assertNotNull(resolved.get());
            assertEquals(Config.PROVIDER_OLLAMA, resolved.get().effectiveProvider());
            assertEquals("durable-model", resolved.get().model());
            assertEquals(131_072,
                    resolved.get().effectiveContextWindowTokens().intValue());
            assertTrue(responses.isEmpty());
            var terminal = adapter.lastResult().orElseThrow();
            assertEquals(TaskSubmissionResult.Status.COMPLETED, terminal.status());
            assertEquals("FOLLOW_UP_COMPLETED", terminal.code());
            assertEquals(admitted.child().taskId().value(), terminal.taskId());
            assertEquals(admitted.child().runId().value(), terminal.runId());
            assertTrue(adapter.activeHandle().isEmpty());
            assertNull(session.snapshot().currentTaskId());
            assertEquals(TaskStore.STATUS_DONE, state.tasks.findById(
                    admitted.child().taskId().value()).orElseThrow().status());
            var codingRun = state.codingRuns.findById(
                    admitted.child().runId().value()).orElseThrow();
            assertEquals(admitted.child().taskId().value(), codingRun.taskId());
            assertEquals(CodingRunStore.PHASE_COMPLETED, codingRun.phase());
            var durableRun = state.r1aDurable.findRun(
                    admitted.child().runId()).orElseThrow();
            var generation = state.r1aDurable.findGeneration(
                    admitted.child().generationId()).orElseThrow();
            assertEquals(admitted.child().taskId(), durableRun.taskId());
            assertEquals(admitted.child().taskId(), generation.taskId());
            assertEquals(admitted.child().runId(), generation.createdByRunId());
            assertEquals(1, state.r1aDurable.findRunsByTask(
                    admitted.child().taskId()).size());
            assertEquals(com.paicli.state.PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                    state.planAuthorities.readActive(admitted.child().taskId()).status());
            CanonicalId planRevision = state.planAuthorities.readLatestApprovedRevision(
                    admitted.child().taskId()).revision().planRevisionId();
            var planEvidence = state.planCompletions.readEvidence(
                    admitted.child().taskId(), planRevision).stream()
                    .filter(evidence -> evidence.kind()
                            == com.paicli.state.PlanCompletionModels.EvidenceKind.TOOL_CALL)
                    .toList();
            assertTrue(planEvidence.isEmpty(),
                    "Advisory Plan Evidence must not gate the child Task");
            var toolCalls = state.toolCalls.findByTask(
                    admitted.child().taskId().value());
            assertEquals(1, toolCalls.size());
            assertEquals("list_files", toolCalls.getFirst().toolName());
            assertTrue(toolCalls.getFirst().success());
            List<AgentEventType> eventTypes = events.stream()
                    .map(event -> event.metadata().eventType()).toList();
            assertEquals(1, eventTypes.stream()
                    .filter(AgentEventType.TASK_COMPLETED::equals).count());
            assertEquals(0, eventTypes.stream()
                    .filter(AgentEventType.TASK_FAILED::equals).count());
            assertEquals(0, eventTypes.stream()
                    .filter(AgentEventType.TASK_CANCELLED::equals).count());
            assertEquals(0, eventTypes.stream()
                    .filter(AgentEventType.TASK_INCONCLUSIVE::equals).count());
            assertEquals(1, eventTypes.stream()
                    .filter(AgentEventType.TOOL_COMPLETED::equals).count());
            assertEquals(0, eventTypes.stream()
                    .filter(AgentEventType.TOOL_FAILED::equals).count());
            assertTrue(eventTypes.indexOf(AgentEventType.TOOL_COMPLETED)
                    < eventTypes.indexOf(AgentEventType.TASK_COMPLETED));
            assertEquals("2", queryText(workspace,
                    "SELECT COUNT(*) FROM task_identities"));
            assertEquals("1", queryText(workspace,
                    "SELECT COUNT(*) FROM child_initial_run_launches"));
            assertEquals("STARTED", queryText(workspace,
                    "SELECT state FROM child_initial_run_launches WHERE child_task_id='"
                            + admitted.child().taskId().value() + "'"));
        }
    }

    @Test void recoveryRunningResultUsesSameTaskNewResumeRunAndOneInconclusive()
            throws Exception {
        Path workspace = temp.resolve("resume-inconclusive");
        Files.createDirectories(workspace);
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        Config config = config(workspace);
        StateStore state = new StateStore(workspace);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                task, "resume task", 10_000, config,
                RuntimeManifest.disabled(), at);
        state.checkpoints.save(task.value(), 1, "RUNNING",
                List.of(AgentMessage.userGoal("resume task")));
        AtomicReference<TaskRecoveryService.Success> recovery = new AtomicReference<>();
        InteractiveRuntimeTaskExecutor executor = new InteractiveRuntimeTaskExecutor() {
            @Override public CodingTaskResult execute(String taskId,
                    InteractiveTaskRequest request, StateStore store,
                    RuntimeEventBridge bridge) {
                throw new AssertionError("root execution is unused");
            }

            @Override public CodingTaskResult resume(String taskId, StateStore store,
                    RuntimeEventBridge bridge) throws Exception {
                Instant acquiredAt = at.plusSeconds(2);
                var result = new TaskRecoveryService(store,
                        new InstructionManifestRuntime(store, workspace)).recover(
                        task, config, RuntimeManifest.disabled(), 10_000,
                        new TaskRecoveryService.LeaseRequest("resume-owner",
                                "resume-lease", acquiredAt,
                                acquiredAt.plusSeconds(30), Duration.ZERO));
                if (!(result instanceof TaskRecoveryService.Success success)) {
                    throw new AssertionError("real Recovery admission was rejected: "
                            + result);
                }
                recovery.set(success);
                FencedExecutionGuard guard = new FencedExecutionGuard(store,
                        TaskExecutionAuthority.recovery(success.ownership()),
                        Duration.ofSeconds(30), () -> acquiredAt.plusSeconds(1));
                bridge.bindExecutionGuard(guard);
                guard.prepareTaskForResume();
                guard.createCodingRun("", config.effectiveMaxRepairAttempts(),
                        success.startIteration());
                guard.markTaskRunning();
                bridge.taskStarted(taskId, success.resumeRun().id().value(),
                        "resume task");
                return new CodingTaskResult(taskId,
                        success.resumeRun().id().value(), null,
                        TaskStore.STATUS_RUNNING, null, success.startIteration(),
                        "resume execution remains authoritative and non-terminal",
                        guard);
            }
        };
        List<AgentEvent> events = new ArrayList<>();
        StringWriter transcript = new StringWriter();
        PlainRuntimeEventRenderer renderer = new PlainRuntimeEventRenderer(
                new PrintWriter(transcript));
        try (InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, executor, event -> {
                    events.add(event);
                    renderer.accept(event);
                }, new InteractiveRuntimeView(), state)) {
            InteractiveSession session = session(workspace);
            adapter.openSession(session);
            new SavedSessionService(state, workspace,
                    Clock.fixed(at.plusSeconds(1), ZoneOffset.UTC))
                    .bind(session, root.threadId().value());

            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.recoverTask(task.value()).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            renderer.flush();

            TaskRecoveryService.Success resumed = recovery.get();
            assertNotNull(resumed);
            assertEquals(task, resumed.taskId());
            assertNotEquals(root.initialRun().id(), resumed.resumeRun().id());
            assertEquals(root.initialRun().id(),
                    resumed.resumeRun().resumedFromRunId());
            assertEquals("RESUME", resumed.resumeRun().kind());
            assertEquals(TaskSubmissionResult.Status.INCOMPLETE,
                    adapter.lastResult().orElseThrow().status());
            assertEquals("RESUME_INCOMPLETE",
                    adapter.lastResult().orElseThrow().code());
            assertEquals(task.value(), adapter.lastResult().orElseThrow().taskId());
            assertEquals(resumed.resumeRun().id().value(),
                    adapter.lastResult().orElseThrow().runId());
            assertEquals(TaskStore.STATUS_RUNNING,
                    state.tasks.findById(task.value()).orElseThrow().status());
            var codingRun = state.codingRuns
                    .findById(resumed.resumeRun().id().value()).orElseThrow();
            assertEquals(CodingRunStore.KIND_RESUME, codingRun.runKind());
            assertEquals(CodingRunStore.PHASE_PLANNING, codingRun.phase());
            assertNull(state.r1aDurable.findRun(resumed.resumeRun().id())
                    .orElseThrow().terminalStatus());
            assertEquals(resumed.resumeRun().id(), state.r1aDurable
                    .readOwnership(task).orElseThrow().ownerRunId());
            assertTerminalMutualExclusion(events);
            List<String> lines = transcript.toString().lines().toList();
            assertEquals(1, lines.stream()
                    .filter(line -> line.startsWith("[Run] Resumed:")).count());
            assertEquals(0, lines.stream()
                    .filter(line -> line.startsWith("[Task] Started:")).count());
            assertEquals(1, lines.stream()
                    .filter(line -> line.startsWith("[Task] Incomplete · ")).count());
            assertFalse(transcript.toString().contains(task.value()));
            assertFalse(transcript.toString()
                    .contains(root.initialRun().id().value()));
        }
    }

    @Test void terminalControlHandoffPrecedesRendererAndOccursOnce() throws Exception {
        Path workspace = temp.resolve("terminal-handoff");
        Files.createDirectories(workspace);
        AtomicInteger handoffs = new AtomicInteger();
        AtomicReference<InteractiveSession> opened = new AtomicReference<>();
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            state.tasks.createTask(taskId, request.input(), "mock", null,
                    "read-only", 1);
            state.tasks.markRunning(taskId);
            bridge.taskStarted(taskId, "run-terminal", request.input());
            state.tasks.markDone(taskId);
            return new CodingTaskResult(taskId, "run-terminal", null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
        };
        try (InteractiveRuntimeAdapter adapter = InteractiveRuntimeAdapter.open(
                workspace, executor, event -> {
                    if (event.metadata().eventType() == AgentEventType.TASK_COMPLETED
                            || event.metadata().eventType() == AgentEventType.TASK_FAILED) {
                        assertEquals(1, handoffs.get());
                        assertNull(opened.get().snapshot().currentTaskId());
                    }
                })) {
            InteractiveSession session = session(workspace);
            opened.set(session);
            adapter.openSession(session);
            adapter.onTerminal(handoffs::incrementAndGet);
            assertEquals(TaskSubmissionResult.Status.PREPARING,
                    adapter.submit(request(session, workspace, "complete")).status());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals(1, handoffs.get());
            assertNull(session.snapshot().currentTaskId());
        }
    }

    private static InteractiveSession session(Path workspace) {
        InteractiveSession session = new InteractiveSession(
                "session-1", workspace, "mock", null, "read-only",
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        return session;
    }

    private static InteractiveTaskRequest request(
            InteractiveSession session, Path workspace, String input) {
        return new InteractiveTaskRequest(
                session.snapshot().sessionId(), workspace, input, List.of());
    }

    private static void assertTerminalMutualExclusion(List<AgentEvent> events) {
        assertEquals(1, events.stream().filter(event ->
                event.metadata().eventType() == AgentEventType.TASK_INCONCLUSIVE).count());
        assertEquals(0, events.stream().filter(event ->
                event.metadata().eventType() == AgentEventType.TASK_COMPLETED).count());
        assertEquals(0, events.stream().filter(event ->
                event.metadata().eventType() == AgentEventType.TASK_FAILED).count());
        assertEquals(0, events.stream().filter(event ->
                event.metadata().eventType() == AgentEventType.TASK_CANCELLED).count());
    }

    private static Config config(Path workspace) {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model", "", "", "system", 10, "", 120, 50_000,
                1, false, false, false);
    }

    private static void markParentTerminal(Path workspace, CanonicalId task,
            CanonicalId run, Instant at) throws Exception {
        try (DatabaseManager manager = new DatabaseManager(
                workspace.resolve(".paicli/state"));
             Statement statement = manager.getConnection().createStatement()) {
            statement.executeUpdate("UPDATE task_identities SET status='COMPLETED',"
                    + "terminal_at='" + at + "' WHERE task_id='" + task.value() + "'");
            statement.executeUpdate("UPDATE durable_runs SET terminal_status='COMPLETED'"
                    + " WHERE run_id='" + run.value() + "'");
            statement.executeUpdate("UPDATE tasks SET status='DONE',completed_at='"
                    + at + "' WHERE task_id='" + task.value() + "'");
            statement.executeUpdate("UPDATE coding_runs SET phase='COMPLETED',"
                    + "completed_at='" + at + "' WHERE run_id='" + run.value() + "'");
        }
    }

    private static String queryText(Path workspace, String sql) throws Exception {
        try (DatabaseManager manager = new DatabaseManager(
                workspace.resolve(".paicli/state"));
             Statement statement = manager.getConnection().createStatement();
             var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
