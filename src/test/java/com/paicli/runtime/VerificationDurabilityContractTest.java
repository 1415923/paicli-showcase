package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.config.ProjectInstructions;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.ModelResponse;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ContextRebuilder;
import com.paicli.state.DatabaseManager;
import com.paicli.state.MigrationRunner;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aStoreResults.FencedMutationResult;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationDurabilityContractTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("VERIFY_PASSED_RECORD_DURABLE_BEFORE_RUN_COMPLETED / "
            + "VERIFY_PASSED_RECORD_IMMEDIATELY_QUERYABLE")
    void passedRecordIsCommittedAndImmediatelyQueryableBeforeTerminalization()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);
            AtomicInteger launches = new AtomicInteger();

            VerificationResult result = service(state, launches).verify(
                    "verify-durable",
                    admitted.root().initialRun().id().value(),
                    admitted.root().taskId().value(),
                    0, "verify", admitted.guard());

            assertEquals(VerificationStore.STATUS_PASSED, result.status());
            assertEquals(1, launches.get());
            VerificationStore.VerificationRecord local = state.verifications
                    .findByRun(admitted.root().initialRun().id().value())
                    .getFirst();
            assertEquals(VerificationStore.STATUS_PASSED, local.status());
            assertNotEquals(CodingRunStore.PHASE_COMPLETED,
                    state.codingRuns.findById(local.runId()).orElseThrow().phase());
            assertNotEquals(TaskStore.STATUS_DONE,
                    state.tasks.findById(local.taskId()).orElseThrow().status());

            try (StateStore observer = new StateStore(workspace)) {
                VerificationStore.VerificationRecord visible = observer.verifications
                        .findByRun(local.runId()).getFirst();
                assertEquals(local.verificationId(), visible.verificationId());
                assertEquals(VerificationStore.STATUS_PASSED, visible.status());
            }

            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    () -> admitted.guard().terminalize(null, true, null),
                    "verification PASS alone cannot bypass the durable UserResult gate");
            assertNotTerminal(state, admitted);
        }
    }

    @Test
    @DisplayName("VERIFY_RECORD_BOUND_TO_AUTHORITATIVE_RUN / "
            + "VERIFY_RECORD_NOT_WRITTEN_TO_FOREIGN_RUN")
    void fencedVerificationUsesAuthoritativeScopeInsteadOfCallerLabels()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);

            service(state, new AtomicInteger()).verify(
                    "verify-authoritative", "foreign-run", "foreign-task",
                    0, "verify", admitted.guard());

            VerificationStore.VerificationRecord stored = state.verifications
                    .findById("verify-authoritative").orElseThrow();
            assertEquals(admitted.root().taskId().value(), stored.taskId());
            assertEquals(admitted.root().initialRun().id().value(), stored.runId());
            assertTrue(state.verifications.findByRun("foreign-run").isEmpty());
        }
    }

    @Test
    @DisplayName("VERIFY_INITIAL_RECORD_FAILURE_FAILS_CLOSED")
    void initialRecordFailurePreventsProcessLaunchAndLeavesNoRecord()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);
            installAbortTrigger(state, """
                    CREATE TRIGGER test_abort_verification_insert
                    BEFORE INSERT ON verification_runs
                    BEGIN SELECT RAISE(ABORT,'injected verification insert failure'); END
                    """);
            AtomicInteger launches = new AtomicInteger();

            FencedExecutionGuard.AuthorityLostException rejected = assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    () -> service(state, launches).verify(
                    "verify-insert-failure",
                    admitted.root().initialRun().id().value(),
                    admitted.root().taskId().value(),
                    0, "verify", admitted.guard()));

            assertEquals("EXECUTION_MUTATION_INTEGRITY_VIOLATION", rejected.code());
            assertEquals("VERIFICATION_START_WRITE", rejected.boundary());
            assertEquals(0, launches.get());
            assertTrue(state.verifications.findById("verify-insert-failure").isEmpty());
            assertNotTerminal(state, admitted);
            assertEquals(0, count(state, "verification_runs"));
            assertNoSuccessArtifacts(state);
        }
    }

    @Test
    @DisplayName("VERIFY_FINAL_COMMIT_FAILURE_FAILS_CLOSED / "
            + "VERIFY_NO_SUCCESS_RESULT_BEFORE_DURABLE_RECORD / "
            + "VERIFY_NO_COMPLETION_DECISION_BEFORE_DURABLE_RECORD / "
            + "VERIFY_NO_COMPLETION_EVIDENCE_BEFORE_DURABLE_RECORD / "
            + "VERIFY_FAILED_WRITE_DOES_NOT_FABRICATE_PASSED / "
            + "VERIFY_TASK_RUN_NOT_COMPLETED_ON_WRITE_FAILURE")
    void finalPassedCommitFailureRollsBackAndCannotPublishSuccess()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);
            installAbortTrigger(state, """
                    CREATE TRIGGER test_abort_verification_pass
                    BEFORE UPDATE OF status ON verification_runs
                    WHEN NEW.status='PASSED'
                    BEGIN SELECT RAISE(ABORT,'injected verification final commit failure'); END
                    """);
            AtomicInteger launches = new AtomicInteger();

            assertThrows(IllegalStateException.class, () -> service(state, launches).verify(
                    "verify-final-failure",
                    admitted.root().initialRun().id().value(),
                    admitted.root().taskId().value(),
                    0, "verify", admitted.guard()));

            assertEquals(1, launches.get());
            VerificationStore.VerificationRecord stored = state.verifications
                    .findById("verify-final-failure").orElseThrow();
            assertEquals(VerificationStore.STATUS_RUNNING, stored.status());
            assertFalse(state.verifications.findByRun(stored.runId()).stream()
                    .anyMatch(row -> VerificationStore.STATUS_PASSED.equals(row.status())));
            assertTrue(connection(state).getAutoCommit());
            assertNotTerminal(state, admitted);
            assertNoSuccessArtifacts(state);
            assertEquals(1, state.r1aDurable.findRecoveryBlockingOccurrences(
                    admitted.root().taskId(), admitted.root().initialRun().id(),
                    "VERIFICATION").size());
        }
    }

    @Test
    @DisplayName("VERIFY_NO_SUCCESS_RESULT_BEFORE_DURABLE_RECORD_THROUGH_RUNNER / "
            + "VERIFY_TASK_RUN_NOT_COMPLETED_ON_WRITE_FAILURE_THROUGH_RUNNER")
    void productionRunnerCannotTerminalizeAfterFinalVerificationCommitFailure()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            installAbortTrigger(state, """
                    CREATE TRIGGER test_abort_runner_verification_pass
                    BEFORE UPDATE OF status ON verification_runs
                    WHEN NEW.status='PASSED'
                    BEGIN SELECT RAISE(ABORT,'injected runner verification commit failure'); END
                    """);
            AtomicInteger launches = new AtomicInteger();
            Config config = verificationConfig();
            ModelProvider provider = (messages, tools) ->
                    ModelResponse.finalAnswer("fixture completed");
            VerificationService verification = service(state, launches);
            CodingTaskRunner runner = new CodingTaskRunner(
                    state,
                    new PlanService(provider, state.plans),
                    verification,
                    taskId -> new AgentLoop(new ContextualModelProvider(provider,state,workspace,
                            taskId,config.effectiveProvider(),config.model(),OperationKind.CHAT,
                            new CapabilityProfile("test",30_000,
                                    LimitProvenance.CONFIGURED_LIMIT,256,64),false),
                            new ToolRegistry(),
                            config.effectiveMaxIterations(),
                            new ApprovalPolicy(config, taskId), null, state, taskId,
                            config.effectiveProvider(), config.model()),
                    config,
                    new PersistenceSanitizer(),
                    null,
                    null,
                    RuntimeManifest.disabled(),
                    CodingTaskObserver.NOOP,
                    CandidateVerificationGate.ALLOW,
                    new InstructionManifestRuntime(state, workspace));
            String taskId = CanonicalId.newId(EntityFamily.TASK).value();

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runner.run(taskId, "verify durability"));

            assertEquals("Fenced mutation persistence failure", failure.getMessage());
            assertEquals(1, launches.get());
            TaskStore.TaskRecord task = state.tasks.findById(taskId).orElseThrow();
            CodingRunStore.CodingRunRecord run = state.codingRuns
                    .findByTask(taskId).getFirst();
            assertNotEquals(TaskStore.STATUS_DONE, task.status());
            assertNotEquals(CodingRunStore.PHASE_COMPLETED, run.phase());
            VerificationStore.VerificationRecord verificationRow = state.verifications
                    .findByRun(run.runId()).getFirst();
            assertEquals(VerificationStore.STATUS_RUNNING, verificationRow.status());
            assertNoSuccessArtifacts(state);
        }
    }

    @Test
    @DisplayName("VERIFY_FENCED_MUTATION_AUTHORITY_REJECTION")
    void rejectedAuthorityCannotCreateVerificationForAnotherRun()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);
            ExecutionAuthoritySnapshot current = admitted.guard().current().snapshot();
            CanonicalId foreignRun = CanonicalId.newId(EntityFamily.RUN);
            ExecutionAuthoritySnapshot foreign = new ExecutionAuthoritySnapshot(
                    current.taskId(), foreignRun, current.ownerInstanceId(),
                    current.leaseToken(), current.fencingToken(), current.casVersion());

            FencedMutationResult result = state.fencedMutations.startVerification(
                    foreign, Instant.now(), "verify-foreign-authority", 0,
                    "verify", VerificationStore.STATUS_RUNNING,WorkspaceCandidateDigest.KIND,
                    "a".repeat(64),null);

            assertEquals(FencedMutationResult.RUN_MISMATCH, result);
            assertTrue(state.verifications.findById(
                    "verify-foreign-authority").isEmpty());
            assertTrue(state.verifications.findByRun(foreignRun.value()).isEmpty());
        }
    }

    @Test
    @DisplayName("VERIFY_EXACTLY_ONCE_RECORD")
    void duplicateStartOrFinishCannotCreateOrRewriteVerification()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            R3ContextTestFixtures.Admitted admitted =
                    R3ContextTestFixtures.admit(state, workspace);
            FencedExecutionGuard guard = admitted.guard();
            String runId = admitted.root().initialRun().id().value();
            String verificationId = "verify-exactly-once";

            guard.startVerification(verificationId, 0, "verify",
                    VerificationStore.STATUS_RUNNING,WorkspaceCandidateDigest.KIND,
                    "b".repeat(64),null);
            FencedExecutionGuard.AuthorityLostException duplicateStart = assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    () -> guard.startVerification(verificationId, 0, "verify",
                            VerificationStore.STATUS_RUNNING,WorkspaceCandidateDigest.KIND,
                            "b".repeat(64),null));
            assertEquals("EXECUTION_MUTATION_INTEGRITY_VIOLATION",
                    duplicateStart.code());

            guard.finishVerification(verificationId,
                    VerificationStore.STATUS_PASSED, 0, "ok", null, 1);
            FencedExecutionGuard.AuthorityLostException duplicateFinish = assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    () -> guard.finishVerification(verificationId,
                            VerificationStore.STATUS_PASSED, 0, "ok", null, 1));
            assertEquals("EXECUTION_MUTATION_TARGET_NOT_FOUND",
                    duplicateFinish.code());

            assertEquals(1, state.verifications.findByRun(runId).size());
            assertEquals(VerificationStore.STATUS_PASSED,
                    state.verifications.findById(verificationId).orElseThrow().status());
        }
    }

    @Test
    void recoveryReusesPassedVerificationOnlyWhileCandidateIsUnchanged() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (StateStore state = preparedResumeState("task-reuse", "run-reuse")) {
            VerificationService verification = service(state, launches);
            verification.verify("verify-reuse", "run-reuse", "task-reuse", 0, "verify");

            CodingTaskResult result = recoveryRunner(state, verification)
                    .resume(resumeContext("task-reuse"));

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(1, launches.get(), "durable PASS must be reused without relaunch");
            assertEquals(1, state.verifications.findByRun("run-reuse").size());
        }
    }

    @Test
    void recoveryRejectsStalePassedVerificationAndReverifiesCurrentCandidate() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (StateStore state = preparedResumeState("task-stale", "run-stale")) {
            VerificationService verification = service(state, launches);
            verification.verify("verify-stale-a", "run-stale", "task-stale", 0, "verify");
            Files.writeString(workspace.resolve("changed-after-crash.java"), "candidate-b");

            CodingTaskResult result = recoveryRunner(state, verification)
                    .resume(resumeContext("task-stale"));

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(2, launches.get(), "stale PASS must not be reused");
            List<VerificationStore.VerificationRecord> records =
                    state.verifications.findByRun("run-stale");
            assertEquals(2, records.size());
            assertEquals(VerificationStore.STATUS_PASSED, records.get(0).status());
            assertEquals(VerificationStore.STATUS_PASSED, records.get(1).status());
            assertNotEquals(records.get(0).candidateDigest(), records.get(1).candidateDigest());
        }
    }

    @Test
    void interruptedRunningRecoveryRemainsErrorAndCannotAuthorizeCompletion() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (StateStore state = preparedResumeState("task-running", "run-running")) {
            WorkspaceCandidateDigest.Result candidate =
                    new WorkspaceCandidateDigest(workspace).compute();
            state.verifications.recordStarted("verify-interrupted", "run-running",
                    "task-running", 0, "verify", candidate.kind(), candidate.digest());
            VerificationService verification = service(state, launches);

            CodingTaskResult result = recoveryRunner(state, verification)
                    .resume(resumeContext("task-running"));

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus(), result.summary());
            assertEquals(0, launches.get());
            List<VerificationStore.VerificationRecord> records =
                    state.verifications.findByRun("run-running");
            assertEquals(1, records.size());
            assertEquals(VerificationStore.STATUS_ERROR, records.get(0).status());
        }
    }

    @Test
    void skippedRecoveryRemainsOptionalAndDoesNotLaunchVerifier() throws Exception {
        AtomicInteger launches = new AtomicInteger();
        try (StateStore state = preparedResumeState("task-skipped", "run-skipped")) {
            state.verifications.recordSkipped("verify-skipped", "run-skipped",
                    "task-skipped", 0);
            VerificationService verification = service(state, launches);

            CodingTaskResult result = recoveryRunner(state, verification)
                    .resume(resumeContext("task-skipped"));

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus(), result.summary());
            assertEquals(VerificationStore.STATUS_SKIPPED, result.verificationStatus());
            assertEquals(0, launches.get());
        }
    }

    @Test
    void migratedHistoricalPassWithoutDigestCannotAuthorizeRecoveryReuse() throws Exception {
        Path historicalWorkspace = workspace.resolve("historical");
        Files.createDirectories(historicalWorkspace);
        try (DatabaseManager manager = new DatabaseManager(
                historicalWorkspace.resolve(".paicli/state"))) {
            MigrationRunner migrations = new MigrationRunner(manager.getConnection());
            var through = MigrationRunner.class.getDeclaredMethod("runMigrationsThrough",
                    int.class);
            through.setAccessible(true);
            through.invoke(migrations, 29);
            try (Statement statement = manager.getConnection().createStatement()) {
                statement.execute("INSERT INTO tasks(task_id,goal,status) "
                        + "VALUES('task-historical','goal','RUNNING')");
                statement.execute("INSERT INTO coding_runs(run_id,task_id,run_kind,phase,"
                        + "verification_command,max_repair_attempts,started_iteration,last_iteration) "
                        + "VALUES('run-historical','task-historical','INITIAL','VERIFYING',"
                        + "'verify',1,0,0)");
                statement.execute("INSERT INTO verification_runs(verification_id,run_id,task_id,"
                        + "repair_attempt,command,status,exit_code,duration_ms,completed_at) "
                        + "VALUES('verify-historical','run-historical','task-historical',0,"
                        + "'verify','PASSED',0,1,datetime('now'))");
            }
        }
        AtomicInteger launches = new AtomicInteger();
        try (StateStore state = new StateStore(historicalWorkspace)) {
            VerificationService verification = new VerificationService(historicalWorkspace,
                    state.verifications, new PersistenceSanitizer(), 5, 4096,
                    (path, command) -> {
                        launches.incrementAndGet();
                        return new CompletedProcess("verification-passed\n", "", 0);
                    });

            CodingTaskResult result = recoveryRunner(state, verification)
                    .resume(resumeContext("task-historical"));

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(1, launches.get(), "historical null digest PASS must be reverified");
            List<VerificationStore.VerificationRecord> records =
                    state.verifications.findByRun("run-historical");
            assertEquals(2, records.size());
            assertEquals(null, records.get(0).candidateDigest());
            assertTrue(records.get(1).candidateDigest().matches("[0-9a-f]{64}"));
        }
    }

    private VerificationService service(StateStore state, AtomicInteger launches)
            throws Exception {
        return new VerificationService(workspace, state.verifications,
                new PersistenceSanitizer(), 5, 4096,
                (path, command) -> {
                    launches.incrementAndGet();
                    return new CompletedProcess("verification-passed\n", "", 0);
                });
    }

    private StateStore preparedResumeState(String taskId, String runId) throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask(taskId, "goal", "mock", null, Config.MODE_ON_REQUEST, 3);
        state.codingRuns.create(runId, taskId, CodingRunStore.KIND_INITIAL, "verify", 1, 0);
        state.codingRuns.transitionPhase(runId, CodingRunStore.PHASE_PLANNING,
                CodingRunStore.PHASE_EXECUTING);
        state.codingRuns.transitionPhase(runId, CodingRunStore.PHASE_EXECUTING,
                CodingRunStore.PHASE_VERIFYING);
        return state;
    }

    private CodingTaskRunner recoveryRunner(StateStore state, VerificationService verification) {
        Config config = verificationConfig();
        ModelProvider provider = (messages, tools) -> {
            throw new AssertionError("recovery verification must not call Provider");
        };
        return new CodingTaskRunner(state, new PlanService(provider, state.plans), verification,
                taskId -> new AgentLoop(provider, new ToolRegistry(),
                        config.effectiveMaxIterations(), new ApprovalPolicy(config, taskId),
                        null, state, taskId, config.effectiveProvider(), config.model()),
                config, new PersistenceSanitizer(), null, null,
                RuntimeManifest.disabled(), CodingTaskObserver.NOOP,
                CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, workspace));
    }

    private ContextRebuilder.ResumedContext resumeContext(String taskId) {
        return new ContextRebuilder.ResumedContext(taskId, "goal", List.of(), 0, "",
                new ProjectInstructions("", List.of(), 0, 0));
    }

    private Config verificationConfig() {
        return new Config(
                workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_ON_REQUEST, 10_000,
                Config.PROVIDER_MOCK, null, null, null, null, 3,
                "verify", 5, 4096, 1,
                false, true, false);
    }

    private static void assertNotTerminal(
            StateStore state, R3ContextTestFixtures.Admitted admitted)
            throws Exception {
        assertNotEquals(CodingRunStore.PHASE_COMPLETED,
                state.codingRuns.findById(admitted.root().initialRun().id().value())
                        .orElseThrow().phase());
        assertNotEquals(TaskStore.STATUS_DONE,
                state.tasks.findById(admitted.root().taskId().value())
                        .orElseThrow().status());
    }

    private static void assertNoSuccessArtifacts(StateStore state) throws Exception {
        assertEquals(0, count(state, "task_user_results"));
        assertEquals(0, count(state, "task_completion_decisions"));
        assertEquals(0, count(state, "plan_evidence"));
        assertEquals(0, count(state, "plan_verification_evidence"));
    }

    private static void installAbortTrigger(StateStore state, String sql)
            throws Exception {
        try (Statement statement = connection(state).createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(StateStore state, String table) throws Exception {
        try (Statement statement = connection(state).createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT count(*) FROM " + table)) {
            return rows.getLong(1);
        }
    }

    private static Connection connection(StateStore state) throws Exception {
        var field = StateStore.class.getDeclaredField("connection");
        field.setAccessible(true);
        return (Connection) field.get(state);
    }

    private static final class CompletedProcess extends Process {
        private final InputStream stdout;
        private final InputStream stderr;
        private final int exitCode;
        private final OutputStream stdin = new ByteArrayOutputStream();

        private CompletedProcess(String stdout, String stderr, int exitCode) {
            this.stdout = new ByteArrayInputStream(
                    stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(
                    stderr.getBytes(StandardCharsets.UTF_8));
            this.exitCode = exitCode;
        }

        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return true; }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { }
        @Override public Process destroyForcibly() { return this; }
        @Override public boolean isAlive() { return false; }
    }
}
