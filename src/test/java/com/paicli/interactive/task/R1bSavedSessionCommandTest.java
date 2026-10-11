package com.paicli.interactive.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.InteractiveRuntimeTaskExecutor;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.state.StateStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class R1bSavedSessionCommandTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    @TempDir Path workspace;

    @Test
    void sessionsContainsCurrentRowFailureAndContinuesHealthyRecoveryAndChildRows()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"),"stable\n");
        Seed a;Seed b;Seed c;Seed d;
        try(StateStore state=new StateStore(workspace)){
            a=seed(state);b=seed(state);c=seed(state);d=seed(state);
            try(DatabaseManager external=new DatabaseManager(workspace.resolve(".paicli/state"));
                Statement sql=external.getConnection().createStatement()){
                sql.execute("PRAGMA ignore_check_constraints=ON");
                sql.executeUpdate("UPDATE durable_runs SET created_at='bad' WHERE task_id='"
                        +b.task().value()+"'");
                sql.executeUpdate("UPDATE task_identities SET status='COMPLETED',terminal_at='"
                        +AT+"' WHERE task_id='"+d.task().value()+"'");
                sql.executeUpdate("UPDATE durable_runs SET terminal_status='COMPLETED' WHERE task_id='"
                        +d.task().value()+"'");
                sql.executeUpdate("UPDATE tasks SET status='DONE',completed_at='"+AT
                        +"' WHERE task_id='"+d.task().value()+"'");
                sql.executeUpdate("UPDATE coding_runs SET phase='COMPLETED',completed_at='"+AT
                        +"' WHERE task_id='"+d.task().value()+"'");
            }
            var follow=new FollowUpService(state,new InstructionManifestRuntime(state,workspace),
                    config(),RuntimeManifest.disabled(),10_000).begin(d.thread(),
                    "continue child",true,"owner","session",AT.plusSeconds(2));
            assertNotNull(follow.child());
        }
        InteractiveSession session=session();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_RECOVERABLE,
                b.thread(),b.task(),"RUNNING",AT));
        CountingPort runtime=new CountingPort();
        List<String> expectedOrder;
        try(StateStore state=new StateStore(workspace)){
            var threads=new java.util.ArrayList<>(state.r1aDurable.listWorkThreads(
                    com.paicli.interactive.session.SavedSessionService.workspaceIdentity(workspace)));
            threads.sort(java.util.Comparator.comparing(
                    com.paicli.state.R1aDurableModels.WorkThread::updatedAt).reversed()
                    .thenComparing(item->item.id().value()));
            expectedOrder=threads.stream().map(item->item.id().shortDisplay()).toList();
        }
        var result=commands(runtime).sessions(session);
        assertEquals("SESSIONS",result.code());
        String bRow=result.lines().stream().filter(line->line.startsWith(b.thread().shortDisplay()))
                .findFirst().orElseThrow();
        assertTrue(bRow.contains("INTEGRITY_FAILURE"),bRow);
        assertFalse(bRow.contains("RECOVERY_REQUIRED"),bRow);
        assertTrue(result.lines().stream().anyMatch(line->line.startsWith(a.thread().shortDisplay())
                &&line.contains("RECOVERY_REQUIRED")),result.lines().toString());
        assertTrue(result.lines().stream().anyMatch(line->line.startsWith(c.thread().shortDisplay())
                &&line.contains("RECOVERY_REQUIRED")),result.lines().toString());
        assertTrue(result.lines().stream().anyMatch(line->line.startsWith(d.thread().shortDisplay())
                &&line.contains("CHILD_INITIAL_RUN_PENDING")),result.lines().toString());
        assertFalse(result.lines().stream().anyMatch(line->line.startsWith(d.thread().shortDisplay())
                &&line.contains("RECOVERY_REQUIRED")),result.lines().toString());
        assertEquals(0,runtime.submits.get());assertEquals(0,runtime.recovers.get());
        List<String> rows=result.lines().stream().filter(line->line.contains(" | ")).toList();
        assertEquals(expectedOrder,rows.stream()
                .map(line->line.substring(0,line.indexOf(" | "))).toList());
    }

    @Test
    void sessionsAndResumeBindWithoutRunOwnershipOrProviderAndNewOnlyUnbinds()
            throws Exception {
        Seed seed;
        try (StateStore state = new StateStore(workspace)) {
            seed = seed(state);
        }
        CountingPort runtime = new CountingPort();
        InteractiveTaskCommandService commands = commands(runtime);
        InteractiveSession session = session();
        int runsBefore;
        try (StateStore state = new StateStore(workspace)) {
            runsBefore = state.r1aDurable.findRunsByTask(seed.task()).size();
        }

        var listed = commands.sessions(session);
        var bound = commands.resume(List.of(seed.thread().shortDisplay()), session);

        assertEquals("SESSIONS", listed.code());
        assertEquals("RESUME_SUCCESS", bound.code());
        assertEquals(seed.thread(), session.snapshot().binding().threadId());
        assertEquals(0, runtime.submits.get());
        assertEquals(0, runtime.recovers.get());
        try (StateStore state = new StateStore(workspace)) {
            assertEquals(runsBefore, state.r1aDurable.findRunsByTask(seed.task()).size());
            assertTrue(state.r1aDurable.readOwnership(seed.task()).isEmpty());
        }

        long generation = session.snapshot().contextGeneration();
        assertEquals("NEW_UNBOUND", commands.newSession(session).code());
        assertEquals(com.paicli.interactive.session.SessionBinding.State.UNBOUND,
                session.snapshot().binding().state());
        assertEquals(generation + 1, session.snapshot().contextGeneration());
        try (StateStore state = new StateStore(workspace)) {
            assertTrue(state.r1aDurable.findWorkThread(seed.thread()).isPresent());
            assertEquals(seed.task(), state.r1aDurable
                    .readThreadHead(seed.thread()).orElseThrow().headTaskId());
        }
        assertEquals("RESUME_SUCCESS",
                commands.resume(List.of(seed.thread().value()), session).code());
    }

    @Test
    void taskRecoverIsTheOnlyCommandThatCallsRecoveryRuntime() throws Exception {
        Seed seed;
        Seed other;
        try (StateStore state = new StateStore(workspace)) {
            seed = seed(state);
            other = seed(state);
        }
        CountingPort runtime = new CountingPort();
        InteractiveTaskCommandService commands = commands(runtime);
        InteractiveSession session = session();

        var listed = commands.sessions(session);
        assertEquals("SESSIONS", listed.code());
        assertTrue(listed.lines().stream().anyMatch(line ->
                line.startsWith(seed.thread().shortDisplay()+" | ")));
        assertTrue(listed.lines().stream().anyMatch(line ->
                line.startsWith(other.thread().shortDisplay()+" | ")));
        assertEquals(0, runtime.submits.get());
        assertEquals(0, runtime.recovers.get());

        assertEquals("NO_BOUND_SESSION",
                commands.recover(List.of("recover"), session).code());
        assertEquals("RESUME_SUCCESS",
                commands.resume(List.of(seed.thread().value()), session).code());
        assertEquals(0, runtime.recovers.get());
        assertEquals("TASK_NOT_IN_BOUND_THREAD",
                commands.recover(List.of("recover", other.task().value()), session).code());

        var result = commands.recover(
                List.of("recover", seed.task().value()), session);

        assertEquals("ACCEPTED", result.code());
        assertEquals(1, runtime.recovers.get());
        assertEquals(seed.task().value(), runtime.recoveredTask);
    }

    @Test
    void continueOnRecoverableTaskRequiresRecoveryWithoutCreatingFollowUp() throws Exception {
        Seed seed;
        try(StateStore state=new StateStore(workspace)){seed=seed(state);}
        CountingPort runtime=new CountingPort();
        InteractiveTaskCommandService commands=commands(runtime);
        InteractiveSession session=session();
        assertEquals("RESUME_SUCCESS",
                commands.resume(List.of(seed.thread().value()),session).code());

        var result=commands.continueTask(List.of("continue","work"),session);

        assertEquals("TASK_RECOVERY_REQUIRED",result.code());
        assertEquals(0,runtime.submits.get());
        assertEquals(0,runtime.recovers.get());
    }

    @Test
    void resumeLastIsWorkspaceScopedAndNoArgumentNeverGuesses() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            seed(state);
        }
        InteractiveTaskCommandService commands = commands(new CountingPort());
        InteractiveSession session = session();

        assertEquals("RESUME_SELECTOR_REQUIRED",
                commands.resume(List.of(), session).code());
        assertEquals("RESUME_SUCCESS",
                commands.resume(List.of("--last"), session).code());
    }

    @Test
    void emptySessionsAndLastReturnExplicitEmptyResults() {
        InteractiveTaskCommandService commands = commands(new CountingPort());

        assertEquals("SESSIONS_EMPTY", commands.sessions(session()).code());
        assertEquals("NOT_FOUND",
                commands.resume(List.of("--last"), session()).code());
    }

    @Test
    void newThenOrdinaryInputCreatesIndependentRootWithoutChangingOldHead()
            throws Exception {
        Seed old;
        try (StateStore initial = new StateStore(workspace)) {
            old = seed(initial);
        }
        Config runtimeConfig = new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model-a",
                "", "", "system", 3, "", 120, 50_000, 1,
                false, false, false);
        CountDownLatch durableReady = new CountDownLatch(1);
        CountDownLatch publishAdmission = new CountDownLatch(1);
        InteractiveRuntimeTaskExecutor executor = (taskId, request, state, bridge) -> {
            CanonicalId canonicalTask = CanonicalId.parse(taskId);
            assertEquals(EntityFamily.TASK, canonicalTask.family());
            Instant admittedAt = AT.plusSeconds(2);
            var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                    canonicalTask, request.input(), 10_000, runtimeConfig,
                    RuntimeManifest.disabled(), admittedAt);
            assertEquals(OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(canonicalTask,
                            "foreground-fixture", root.initialRun().id(),
                            "fixture-lease", admittedAt, admittedAt.plusSeconds(30)));
            durableReady.countDown();
            assertTrue(publishAdmission.await(2, TimeUnit.SECONDS));
            bridge.taskStarted(taskId, root.initialRun().id().value(), request.input());
            state.tasks.markDone(taskId);
            return new CodingTaskResult(taskId, root.initialRun().id().value(), null,
                    TaskStore.STATUS_DONE, "SKIPPED", 0, "done");
        };
        StateStore runtimeState = new StateStore(workspace);
        InteractiveRuntimeAdapter runtime = new InteractiveRuntimeAdapter(workspace,
                executor,
                ignored -> { }, new InteractiveRuntimeView(), runtimeState);
        try {
            InteractiveSession session = session();
            runtime.openSession(session);
            InteractiveTaskCommandService commands = commands(runtime);
            assertEquals("RESUME_SUCCESS",
                    commands.resume(List.of(old.thread().value()), session).code());
            assertEquals("NEW_UNBOUND", commands.newSession(session).code());
            assertEquals("ACCEPTED", runtime.admitOrdinaryInput().code());
            assertEquals("PREPARING", runtime.submit(new InteractiveTaskRequest(
                    "session-1", workspace, "independent root", List.of())).code());
            assertTrue(durableReady.await(2, TimeUnit.SECONDS));
            ForegroundTask foreground = runtime.activeForegroundTask().orElseThrow();
            assertFalse(foreground.admission().isDone());
            publishAdmission.countDown();
            ForegroundTask.DurableAdmission receipt =
                    foreground.admission().get(2, TimeUnit.SECONDS);
            CanonicalId admittedTask = CanonicalId.parse(receipt.taskId());
            assertEquals(EntityFamily.TASK, admittedTask.family());
            foreground.terminal().get(2, TimeUnit.SECONDS);
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(5)));

            CanonicalId newTask = session.snapshot().binding().headTaskId();
            assertEquals(admittedTask, newTask);
            assertNotEquals(old.task(), newTask);
            assertNotEquals(old.thread(), session.snapshot().binding().threadId());
            assertEquals(old.task(), runtimeState.r1aDurable
                    .readThreadHead(old.thread()).orElseThrow().headTaskId());
            assertNotEquals(runtimeState.r1aDurable.findTask(old.task()).orElseThrow().manifestId(),
                    runtimeState.r1aDurable.findTask(newTask).orElseThrow().manifestId());
            assertTrue(runtimeState.r1aDurable.readOwnership(old.task()).isEmpty());
        } finally {
            runtime.close();
        }
    }

    private InteractiveTaskCommandService commands(TaskSubmissionPort runtime) {
        return new InteractiveTaskCommandService(workspace,
                InteractiveQueryFacade.unavailable(), runtime, ignored -> null,
                Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));
    }

    private Seed seed(StateStore state) throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        Config config = config();
        Path projectConfig=workspace.resolve(".paicli/config.json");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig,new ObjectMapper().writeValueAsString(config));
        Config frozenConfig=new ConfigLoader(workspace,null).load();
        RuntimeManifest runtimeManifest=new RuntimeManifestHasher(workspace)
                .compute(frozenConfig);
        var admission = new InstructionManifestRuntime(state, workspace).admitRoot(
                task, "recover me", 10_000, frozenConfig, runtimeManifest, AT);
        state.checkpoints.save(task.value(), 1, "RUNNING",
                List.of(AgentMessage.userGoal("recover me")));
        return new Seed(task, admission.threadId());
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, false, true, false);
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-1", workspace,
                Config.PROVIDER_MOCK, "model-a", Config.MODE_WORKSPACE_WRITE,
                InteractiveSession.TerminalMode.PLAIN, AT);
        session.start();
        return session;
    }

    private static final class CountingPort implements TaskSubmissionPort {
        private final AtomicInteger submits = new AtomicInteger();
        private final AtomicInteger recovers = new AtomicInteger();
        private String recoveredTask;

        @Override
        public TaskSubmissionResult submit(InteractiveTaskRequest request) {
            submits.incrementAndGet();
            return TaskSubmissionResult.accepted("accepted");
        }

        @Override
        public TaskSubmissionResult recoverTask(String taskId) {
            recovers.incrementAndGet();
            recoveredTask = taskId;
            return TaskSubmissionResult.accepted("accepted");
        }
    }

    private record Seed(CanonicalId task, CanonicalId thread) { }
}
