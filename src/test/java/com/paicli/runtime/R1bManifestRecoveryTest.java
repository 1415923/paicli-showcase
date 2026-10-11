package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.runtime.InteractiveRuntimeAdapter;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.state.DatabaseManager;
import com.paicli.state.R1aDurableModels.SideEffectOccurrence;
import com.paicli.state.R1aStoreResults.OccurrenceAdmissionResult;
import com.paicli.state.StateStore;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanCompletionStore;
import com.paicli.state.PlanExecutionStore;
import com.paicli.state.PlanToolRequirementPolicy;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class R1bManifestRecoveryTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-07-16T00:00:00Z");

    @Test
    void rootAdmissionCommitsStableOrderedArtifactsAndInitialRunAuthority() throws Exception {
        Files.writeString(workspace.resolve("PAICLI.md"), "paicli-rule");
        Files.writeString(workspace.resolve("AGENTS.md"), "agents-rule");
        try (StateStore state = new StateStore(workspace)) {
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            InstructionManifestRuntime runtime = new InstructionManifestRuntime(state, workspace);
            var root = runtime.admitRoot(task, "goal", 10_000, config(),
                    RuntimeManifest.disabled(), AT);

            assertEquals(List.of("PAICLI.md", "AGENTS.md"),
                    root.instructions().manifest().entries().stream()
                            .map(entry -> entry.sourcePath()).toList());
            assertEquals(List.of(0, 1), root.instructions().manifest().entries().stream()
                    .map(entry -> entry.appliedOrder()).toList());
            assertTrue(root.instructions().prompt().contains("paicli-rule"));
            assertTrue(root.instructions().prompt().contains("agents-rule"));
            assertEquals(root.instructions().manifest().id(),
                    root.initialRun().manifestId());
            assertEquals(root.initialRun().id(), root.generation().createdByRunId());
            assertEquals(root.initialRun().id(),root.pointer().ownerRunId());
            assertEquals(root.generation().id(),root.pointer().generationId());
            assertFalse(root.instructions().toString().contains("agents-rule"));
        }
    }

    @Test
    void readAndParseFailuresFailClosedAndIgnoredEntriesHaveReasons() throws Exception {
        Files.write(workspace.resolve("AGENTS.md"), new byte[]{(byte) 0xc3, (byte) 0x28});
        try (StateStore state = new StateStore(workspace)) {
            InstructionManifestRuntime runtime = new InstructionManifestRuntime(state, workspace);
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            var failure = assertThrows(InstructionManifestRuntime.AdmissionException.class,
                    () -> runtime.admitRoot(task, "goal",
                            10_000, config(), RuntimeManifest.disabled(), AT));
            assertTrue(failure.candidate().entries().stream()
                    .anyMatch(entry -> entry.status().name().equals("FAILED")));
            assertTrue(failure.candidate().entries().stream()
                    .filter(entry -> entry.status().name().equals("IGNORED"))
                    .allMatch(entry -> entry.exclusionReason() != null
                            && !entry.exclusionReason().isBlank()));
            assertTrue(state.r1aDurable.findTask(task).isEmpty());
            assertTrue(state.r1aDurable.findManifestByTask(task).isEmpty());
        }
    }

    @Test
    void recoveryKeepsTaskAndManifestCreatesNewRunAndUsesFrozenContent() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "frozen-rule");
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(), "owner-a",
                            seed.initialRun().id(), "lease-a", AT, AT.plusSeconds(5)));
            TaskRecoveryService.RecoveryResult result = recovery(state).recover(
                    seed.task(), config(), RuntimeManifest.disabled(), 10_000,
                    lease("owner-b", "lease-b", AT.plusSeconds(20)));
            var success = assertInstanceOf(TaskRecoveryService.Success.class, result);
            assertEquals(seed.task(), success.taskId());
            assertNotEquals(seed.initialRun().id(), success.resumeRun().id());
            assertEquals(seed.initialRun().id(), success.resumeRun().resumedFromRunId());
            assertEquals(seed.manifestId(), success.resumeRun().manifestId());
            assertTrue(success.instructions().prompt().contains("frozen-rule"));
            assertEquals("RESUME", success.resumeRun().kind());
            assertTrue(success.compatibility().compatible());
            assertEquals(InstructionManifestRuntime.RecoveryMaterialStatus.NO_DRIFT,
                    success.instructionDrift().status());
            assertEquals(seed.initialRun().id(),
                    success.sourceGeneration().createdByRunId());
            assertTrue(success.context().occurrenceSafetyChecked());
            assertEquals(PlanApplicability.PLAN_DISABLED,
                    success.context().planApplicability());
            assertNull(success.context().structuredPlan());
            assertEquals(2, success.context().checkpointIteration());
            assertTrue(success.context().instructions().prompt().contains("frozen-rule"));
            assertEquals(2, success.ownership().fencingToken());
            assertEquals(success.taskId(),success.executionAuthority().taskId());
            assertEquals(success.resumeRun().id(),success.executionAuthority().runId());
            assertFalse(success.executionAuthority().toString().contains("lease-b"));
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.FENCING_REJECTED,
                    state.r1aDurable.validateFencing(seed.task(), "lease-a", 1,
                            AT.plusSeconds(21)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"COMPLETED","FAILED","CANCELLED","INTERRUPTED","UNKNOWN"})
    void canonicalTerminalTaskStatusesAreRejectedByRecoveryPreflight(String status)
            throws Exception {
        Path root=workspace.resolve("preflight-"+status.toLowerCase());
        Files.createDirectories(root);
        Files.writeString(root.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(root)){
            Seed seed=seed(state,root,true);
            setCanonicalTaskStatus(root,seed.task(),status);

            RecoveryPreflightValidator.Result result=state.recoveryPreflight.validate(
                    seed.task(),config(),RuntimeManifest.disabled(),10_000,AT.plusSeconds(20));

            assertEquals(RecoveryPreflightValidator.Eligibility.NOT_ELIGIBLE,
                    result.eligibility());
            assertEquals(List.of("TASK_TERMINAL"),result.reasons());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"CREATED","RUNNING"})
    void canonicalNonterminalTaskStatusesRemainRecoveryEligible(String status) throws Exception {
        Path root=workspace.resolve("nonterminal-"+status.toLowerCase());
        Files.createDirectories(root);
        Files.writeString(root.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(root)){
            Seed seed=seed(state,root,true);
            setCanonicalTaskStatus(root,seed.task(),status);

            RecoveryPreflightValidator.Result result=state.recoveryPreflight.validate(
                    seed.task(),config(),RuntimeManifest.disabled(),10_000,AT.plusSeconds(20));

            assertEquals(RecoveryPreflightValidator.Eligibility.ELIGIBLE,
                    result.eligibility(),result.reasons().toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"COMPLETED","FAILED","CANCELLED","INTERRUPTED","UNKNOWN"})
    void recoveryAtomicAdmissionRechecksTerminalStateWithoutMutatingAuthority(String status)
            throws Exception {
        Path root=workspace.resolve("atomic-"+status.toLowerCase());
        Files.createDirectories(root);
        Files.writeString(root.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(root)){
            Seed seed=seed(state,root,true);
            RecoveryPreflightValidator.Result preflight=state.recoveryPreflight.validate(
                    seed.task(),config(),RuntimeManifest.disabled(),10_000,AT.plusSeconds(20));
            assertEquals(RecoveryPreflightValidator.Eligibility.ELIGIBLE,
                    preflight.eligibility(),preflight.reasons().toString());
            int runsBefore=state.r1aDurable.findRunsByTask(seed.task()).size();
            var pointerBefore=state.r1aDurable.readContextPointer(seed.task()).orElseThrow();
            var ownershipBefore=state.r1aDurable.readOwnership(seed.task());
            setCanonicalTaskStatus(root,seed.task(),status);
            var resume=new com.paicli.state.R1aDurableModels.DurableRun(
                    CanonicalId.newId(EntityFamily.RUN),seed.task(),seed.thread(),"RESUME",
                    seed.initialRun().id(),seed.initialRun().manifestId(),
                    seed.initialRun().manifestHash(),AT.plusSeconds(20),null);

            var rejected=assertInstanceOf(
                    com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult.Rejected.class,
                    state.r1aDurable.admitResumeRunForRecovery(resume,"owner-b","lease-b",
                            AT.plusSeconds(20),AT.plusSeconds(80),Duration.ZERO));

            assertEquals(com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult.Reason
                    .TASK_TERMINAL,rejected.reason());
            assertEquals(runsBefore,state.r1aDurable.findRunsByTask(seed.task()).size());
            assertEquals(ownershipBefore,state.r1aDurable.readOwnership(seed.task()));
            assertEquals(pointerBefore,
                    state.r1aDurable.readContextPointer(seed.task()).orElseThrow());
        }
    }

    @Test
    @DisplayName("V3-02 unfinished approved V2 recovery fails closed before resume admission")
    void unfinishedApprovedV2PlanFailsClosedBeforeResumeRunAdmission() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state, true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(), "owner-a",
                            seed.initialRun().id(), "lease-a", AT, AT.plusSeconds(5)));
            var current = state.r1aDurable.readOwnership(seed.task()).orElseThrow();
            ExecutionAuthoritySnapshot authority = new ExecutionAuthoritySnapshot(
                    seed.task(), current.ownerRunId(), current.ownerInstanceId(),
                    current.leaseToken(), current.fencingToken(), current.casVersion());
            CanonicalId planId = seedApprovedV2(seed);
            assertEquals(Revision.CANONICAL_SCHEMA_V2,
                    state.planAuthorities.readRevisionDefinition(seed.task(), planId)
                            .revision().canonicalSchemaVersion());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    state.planExecutions.readRecovery(seed.task(), planId).status());
            assertEquals(PlanCompletionStore.Status.SUCCESS,
                    state.planCompletions.readRecovery(seed.task(), planId).status());
            var legacyStep = state.planAuthorities.readRevisionDefinition(seed.task(), planId)
                    .revision().steps().getFirst();
            var guard = new FencedExecutionGuard(state, TaskExecutionAuthority.initial(current),
                    Duration.ofSeconds(30), () -> AT.plusSeconds(4));
            var executionContext = new com.paicli.agent.AgentSegmentRequest.PlanExecutionContext(
                    seed.task(), planId, legacyStep.stepRevisionId(),
                    seed.initialRun().id(), 1);
            var executionRejected = assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    () -> guard.validatePlanExecutionContext(executionContext));
            assertEquals("PLAN_TOOL_SCOPE_UNAVAILABLE", executionRejected.code());
            long runsBefore = count("durable_runs");

            TaskRecoveryService.RecoveryResult result = recovery(state).recover(
                    seed.task(), config(true), RuntimeManifest.disabled(), 10_000,
                    lease("owner-b", "lease-b", AT.plusSeconds(20)));

            TaskRecoveryService.Rejected rejected = assertInstanceOf(
                    TaskRecoveryService.Rejected.class, result);
            assertEquals(TaskRecoveryService.RecoveryStatus.PLAN_TOOL_SCOPE_UNAVAILABLE,
                    rejected.status());
            assertEquals(List.of("PLAN_TOOL_SCOPE_UNAVAILABLE"), rejected.reasons());
            assertEquals(runsBefore, count("durable_runs"));
            assertEquals(0, count("side_effect_occurrences"));
            assertEquals(0, count("plan_evidence"));
        }
    }

    @Test
    @DisplayName("V3-02 explicit Follow-up on unfinished V2 fails closed before child admission")
    void unfinishedApprovedV2PlanRejectsExplicitFollowUpBeforeChildAdmission()
            throws Exception {
        Seed seed;
        try (StateStore state = new StateStore(workspace)) {
            seed = seedRecoverable(state, true);
            seedApprovedV2(seed);
        }
        StateStore runtimeState = new StateStore(workspace);
        try (InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, (taskId, request, state, bridge) -> {
                    throw new AssertionError("V2 Follow-up must not execute");
                }, ignored -> { }, new InteractiveRuntimeView(), runtimeState)) {
            InteractiveSession session = new InteractiveSession("v2-follow-up-session",
                    workspace, Config.PROVIDER_MOCK, "model-a",
                    Config.MODE_WORKSPACE_WRITE,
                    InteractiveSession.TerminalMode.PLAIN, AT.plusSeconds(20));
            session.start();
            adapter.openSession(session);
            var selected = new SavedSessionService(runtimeState, workspace,
                    Clock.systemUTC()).bind(session, seed.thread().value());
            assertNotNull(selected.view());
            assertEquals(com.paicli.interactive.session.SessionBinding.State.BOUND_RECOVERABLE,
                    selected.view().binding().state());
            long runsBefore = count("durable_runs");

            var rejected = adapter.submitFollowUp(
                    "continue legacy V2 without widening scope", true, null);

            assertEquals("PLAN_TOOL_SCOPE_UNAVAILABLE", rejected.code());
            assertEquals(runsBefore, count("durable_runs"));
            assertEquals(0, count("side_effect_occurrences"));
            assertEquals(0, count("plan_evidence"));
            assertEquals(0, count("followup_admission_facts"));
        }
    }

    @Test
    void recoveryBindsAndRestoresTheSameApprovedStructuredPlan() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "frozen-rule");
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state, true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(), "owner-a",
                            seed.initialRun().id(), "lease-a", AT, AT.plusSeconds(5)));
            ExecutionAuthoritySnapshot initial = new ExecutionAuthoritySnapshot(seed.task(),
                    seed.initialRun().id(), "owner-a", "lease-a", 1, 1);
            CanonicalId planId=approvePlan(state,seed,initial);

            var success = assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(), config(true), RuntimeManifest.disabled(),
                            10_000, lease("owner-b", "lease-b", AT.plusSeconds(20))));
            assertNotNull(success.context().structuredPlan());
            assertEquals(PlanApplicability.PLAN_REQUIRED,
                    success.context().planApplicability());
            assertEquals(planId, success.context().structuredPlan().revision().planRevisionId());
            assertEquals(List.of("Inspect"), success.context().structuredPlan().revision().steps()
                    .stream().map(StepDefinition::title).toList());
            assertNotNull(success.context().planExecution());
            assertEquals(1,success.context().planExecution().states().size());
            assertTrue(success.context().planExecution().attempts().isEmpty());
            assertNotNull(success.context().planCompletion());
            assertEquals(com.paicli.state.PlanCompletionStore.Status.SUCCESS,
                    success.context().planCompletion().status());
            assertEquals(1, success.context().planCompletion().ruleCount());
            assertTrue(success.context().planCompletion().evidence().isEmpty());
            assertNull(success.context().planCompletion().result());
            assertEquals(Revision.CANONICAL_SCHEMA_V3,
                    success.context().structuredPlan().revision()
                    .canonicalSchemaVersion());
            assertEquals(planId, state.planAuthorities.readActive(seed.task())
                    .plan().revision().planRevisionId());
        }
    }

    @Test void runningPlanAttemptBlocksRecoveryWithoutNewResumeAuthority() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seedRecoverable(state,true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(),"owner-a",
                            seed.initialRun().id(),"lease-a",AT,AT.plusSeconds(5)));
            ExecutionAuthoritySnapshot initial=new ExecutionAuthoritySnapshot(seed.task(),
                    seed.initialRun().id(),"owner-a","lease-a",1,1);
            approvePlan(state,seed,initial);
            var started=state.planExecutions.startNextReadyAttempt(
                    initial,AT.plusSeconds(4),0,"test");
            assertEquals(com.paicli.state.PlanExecutionStore.Status.SUCCESS,started.status());
            long runsBefore=count("durable_runs");

            var rejected=assertInstanceOf(TaskRecoveryService.Rejected.class,
                    recovery(state).recover(seed.task(),config(true),RuntimeManifest.disabled(),
                            10_000,lease("owner-b","lease-b",AT.plusSeconds(20))));
            assertEquals(TaskRecoveryService.RecoveryStatus.PLAN_ATTEMPT_UNRESOLVED,
                    rejected.status());
            assertEquals(runsBefore,count("durable_runs"));
            assertEquals("owner-a",state.r1aDurable.readOwnership(seed.task())
                    .orElseThrow().ownerInstanceId());
        }
    }

    @Test void planRequiredRecoveryRejectsCorruptRevisionBeforeResumeAdmission()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        CanonicalId task;CanonicalId initialRun;
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seedRecoverable(state,true);task=seed.task();initialRun=seed.initialRun().id();
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task,"owner-a",initialRun,"lease-a",
                            AT,AT.plusSeconds(5)));
            approvePlan(state,seed,new ExecutionAuthoritySnapshot(task,initialRun,
                    "owner-a","lease-a",1,1));
        }
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement()){
            statement.executeUpdate("DROP TRIGGER r1c_plan_revision_immutable");
            statement.executeUpdate("UPDATE plan_revisions SET canonical_hash='"
                    +"f".repeat(64)+"'");
        }
        try(StateStore state=new StateStore(workspace)){
            int runsBefore=state.r1aDurable.findRunsByTask(task).size();
            var rejected=assertInstanceOf(TaskRecoveryService.Rejected.class,
                    recovery(state).recover(task,config(true),RuntimeManifest.disabled(),10_000,
                            lease("owner-b","lease-b",AT.plusSeconds(20))));
            assertEquals(TaskRecoveryService.RecoveryStatus.PLAN_REVISION_INTEGRITY_FAILURE,
                    rejected.status());
            assertEquals(runsBefore,state.r1aDurable.findRunsByTask(task).size());
        }
    }

    @Test void legacyOnlyPlanRecoveryDoesNotConvertOrReplan()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        CanonicalId task;
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seedRecoverable(state);task=seed.task();
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task,"owner-a",seed.initialRun().id(),
                            "lease-a",AT,AT.plusSeconds(5)));
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    var statement=manager.getConnection().createStatement()){
                statement.executeUpdate("DROP TRIGGER r1c_plan_applicability_no_delete");
                statement.executeUpdate("DELETE FROM task_plan_applicability WHERE task_id='"
                        +task.value()+"'");
                statement.executeUpdate("INSERT INTO task_plans(plan_id,run_id,task_id,plan_text,status) "
                        +"VALUES('legacy-plan','"+seed.initialRun().id().value()+"','"
                        +task.value()+"','legacy','ACTIVE')");
            }
            var rejected=assertInstanceOf(TaskRecoveryService.Rejected.class,
                    recovery(state).recover(task,config(),RuntimeManifest.disabled(),10_000,
                            lease("owner-b","lease-b",AT.plusSeconds(20))));
            assertEquals(TaskRecoveryService.RecoveryStatus.LEGACY_PLAN_CONTINUATION_UNAVAILABLE,
                    rejected.status());
            assertEquals(0,count("plan_revisions"));
        }
    }

    @Test void planRequiredRecoveryWithoutApprovedAuthorityLeavesAllAuthorityUnchanged()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seedRecoverable(state,true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(),"owner-a",
                            seed.initialRun().id(),"lease-a",AT,AT.plusSeconds(5)));
            int runsBefore=state.r1aDurable.findRunsByTask(seed.task()).size();
            var pointerBefore=state.r1aDurable.readContextPointer(seed.task()).orElseThrow();
            var ownershipBefore=state.r1aDurable.readOwnership(seed.task()).orElseThrow();
            var rejected=assertInstanceOf(TaskRecoveryService.Rejected.class,
                    recovery(state).recover(seed.task(),config(true),RuntimeManifest.disabled(),
                            10_000,lease("owner-b","lease-b",AT.plusSeconds(20))));
            assertEquals(TaskRecoveryService.RecoveryStatus.NO_APPROVED_PLAN,rejected.status());
            assertEquals(runsBefore,state.r1aDurable.findRunsByTask(seed.task()).size());
            assertEquals(pointerBefore,state.r1aDurable.readContextPointer(seed.task()).orElseThrow());
            assertEquals(ownershipBefore,state.r1aDurable.readOwnership(seed.task()).orElseThrow());
            assertEquals(0,count("plan_run_bindings"));
        }
    }

    @Test void recoveryPlanBindingFailureRollsBackRunOwnershipAndPointer()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seedRecoverable(state,true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(),"owner-a",
                            seed.initialRun().id(),"lease-a",AT,AT.plusSeconds(5)));
            approvePlan(state,seed,new ExecutionAuthoritySnapshot(seed.task(),
                    seed.initialRun().id(),"owner-a","lease-a",1,1));
            int runsBefore=state.r1aDurable.findRunsByTask(seed.task()).size();
            var pointerBefore=state.r1aDurable.readContextPointer(seed.task()).orElseThrow();
            var ownershipBefore=state.r1aDurable.readOwnership(seed.task()).orElseThrow();
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    var statement=manager.getConnection().createStatement()){
                statement.executeUpdate("CREATE TRIGGER test_fail_recovery_plan_binding "
                        +"BEFORE INSERT ON plan_run_bindings WHEN NEW.run_id<>'"
                        +seed.initialRun().id().value()+"' BEGIN SELECT RAISE(ABORT,'test'); END");
            }
            assertThrows(java.sql.SQLException.class,()->recovery(state).recover(seed.task(),
                    config(true),RuntimeManifest.disabled(),10_000,
                    lease("owner-b","lease-b",AT.plusSeconds(20))));
            assertEquals(runsBefore,state.r1aDurable.findRunsByTask(seed.task()).size());
            assertEquals(pointerBefore,state.r1aDurable.readContextPointer(seed.task()).orElseThrow());
            assertEquals(ownershipBefore,state.r1aDurable.readOwnership(seed.task()).orElseThrow());
            assertEquals(1,count("plan_run_bindings"));
        }
    }

    @Test void missingCompletionRulesRejectRecoveryBeforeResumeAuthority() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "frozen-rule");
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state, true);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(), "owner-a",
                            seed.initialRun().id(), "lease-a", AT, AT.plusSeconds(5)));
            approvePlan(state, seed, new ExecutionAuthoritySnapshot(seed.task(),
                    seed.initialRun().id(), "owner-a", "lease-a", 1, 1));
            int runsBefore = state.r1aDurable.findRunsByTask(seed.task()).size();
            var pointerBefore = state.r1aDurable.readContextPointer(seed.task()).orElseThrow();
            var ownershipBefore = state.r1aDurable.readOwnership(seed.task()).orElseThrow();
            try (DatabaseManager manager = new DatabaseManager(workspace.resolve(".paicli/state"));
                 var statement = manager.getConnection().createStatement()) {
                statement.execute("DROP TRIGGER r1c_completion_requirement_no_delete");
                statement.execute("DROP TRIGGER r1c_completion_rule_no_delete");
                statement.executeUpdate("DELETE FROM plan_completion_requirements");
                statement.executeUpdate("DELETE FROM plan_completion_rules");
            }
            var rejected = assertInstanceOf(TaskRecoveryService.Rejected.class,
                    recovery(state).recover(seed.task(), config(true), RuntimeManifest.disabled(),
                            10_000, lease("owner-b", "lease-b", AT.plusSeconds(20))));
            assertEquals(TaskRecoveryService.RecoveryStatus.COMPLETION_RULES_UNAVAILABLE,
                    rejected.status());
            assertEquals(runsBefore, state.r1aDurable.findRunsByTask(seed.task()).size());
            assertEquals(pointerBefore,
                    state.r1aDurable.readContextPointer(seed.task()).orElseThrow());
            assertEquals(ownershipBefore,
                    state.r1aDurable.readOwnership(seed.task()).orElseThrow());
            assertEquals(1, count("plan_run_bindings"));
        }
    }

    @Test
    void incompatibleWorkspaceDriftAndMissingCheckpointFailBeforeRunCreation() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "original");
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seed(state, false);
            var unavailable = recovery(state).recover(seed.task(), config(),
                    RuntimeManifest.disabled(), 10_000,
                    lease("owner", "lease", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, unavailable).status());

            state.checkpoints.save(seed.task().value(), 1, "RUNNING",
                    List.of(AgentMessage.userGoal("goal")));
            Files.writeString(workspace.resolve("AGENTS.md"), "changed");
            var drift = recovery(state).recover(seed.task(), config(),
                    RuntimeManifest.disabled(), 10_000,
                    lease("owner", "lease", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.TASK_RECOVERY_RUNTIME_DRIFT,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, drift).status());
            assertEquals(seed.initialRun().id(),
                    state.r1aDurable.readContextPointer(seed.task()).orElseThrow().ownerRunId());
        }
    }

    @Test
    void sourceAdditionAndDeletionAreIncompatibleDrift() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agents");
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state);
            Files.writeString(workspace.resolve("PAICLI.md"), "new-source");
            var addition = new InstructionManifestRuntime(state, workspace)
                    .detectDrift(seed.task(), 10_000);
            assertEquals(InstructionManifestRuntime.RecoveryMaterialStatus
                    .INSTRUCTION_DRIFT_INCOMPATIBLE, addition.status());
            assertTrue(addition.reasons().stream()
                    .anyMatch(reason -> reason.startsWith("SOURCE_ADDED:")));
            Files.delete(workspace.resolve("PAICLI.md"));
            Files.delete(workspace.resolve("AGENTS.md"));
            var deletion = new InstructionManifestRuntime(state, workspace)
                    .detectDrift(seed.task(), 10_000);
            assertEquals(InstructionManifestRuntime.RecoveryMaterialStatus
                    .INSTRUCTION_DRIFT_INCOMPATIBLE, deletion.status());
            assertTrue(deletion.reasons().stream()
                    .anyMatch(reason -> reason.startsWith("SOURCE_REMOVED:")));
        }
    }

    @Test
    void unresolvedOccurrenceAndHealthyOwnershipBlockRecoveryWithoutHalfRun() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "rule");
        try (StateStore state = new StateStore(workspace)) {
            Seed occurrenceSeed = seedRecoverable(state);
            SideEffectOccurrence occurrence = new SideEffectOccurrence(
                    CanonicalId.newId(EntityFamily.OCCURRENCE),
                    occurrenceSeed.thread(), occurrenceSeed.task(),
                    occurrenceSeed.initialRun().id(), "iteration-1", "write-key",
                    "call-1", "WRITE", "file", "STARTED", "a".repeat(64),
                    null, null, AT, null);
            assertEquals(OccurrenceAdmissionResult.SUCCESS,
                    state.r1aDurable.admitOccurrence(occurrence));
            assertEquals(
                    com.paicli.state.R1aStoreResults.OccurrenceTransitionResult.SUCCESS,
                    state.r1aDurable.completeOccurrence(occurrence.id(), "UNKNOWN",
                            null, "unresolved", AT.plusSeconds(1)));
            var blocked = recovery(state).recover(occurrenceSeed.task(), config(),
                    RuntimeManifest.disabled(), 10_000,
                    lease("owner-b", "lease-b", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.UNRESOLVED_OCCURRENCE,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, blocked).status());
        }

        Path second = workspace.resolve("ownership");
        Files.createDirectories(second);
        Files.writeString(second.resolve("AGENTS.md"), "rule");
        try (StateStore state = new StateStore(second)) {
            Seed seed = seedRecoverable(state, second);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(seed.task(), "owner-a",
                            seed.initialRun().id(), "lease-a", AT, AT.plusSeconds(100)));
            var blocked = new TaskRecoveryService(state,
                    new InstructionManifestRuntime(state, second)).recover(
                    seed.task(), config(), RuntimeManifest.disabled(), 10_000,
                    lease("owner-b", "lease-b", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.OWNERSHIP_CONFLICT,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, blocked).status());
            assertEquals(seed.initialRun().id(),
                    state.r1aDurable.readContextPointer(seed.task()).orElseThrow().ownerRunId());
        }
    }

    @Test
    void artifactTamperingIsDistinctFromUnavailable() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "protected-rule");
        String artifactId;
        CanonicalId task;
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state);
            task = seed.task();
            artifactId = state.r1aDurable.findManifestByTask(task).orElseThrow()
                    .entries().stream().filter(entry -> entry.artifactId() != null)
                    .findFirst().orElseThrow().artifactId();
        }
        try (DatabaseManager manager = new DatabaseManager(
                workspace.resolve(".paicli/state"))) {
            try (var damage = manager.getConnection().createStatement()) {
                damage.executeUpdate("DROP TRIGGER r1a_artifact_immutable");
            }
            try (var statement = manager.getConnection().prepareStatement(
                    "UPDATE protected_instruction_artifacts SET protected_payload=? WHERE artifact_id=?")) {
                statement.setBytes(1,
                        "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                statement.setString(2, artifactId);
                assertEquals(1, statement.executeUpdate());
            }
        }
        try (StateStore state = new StateStore(workspace)) {
            var result = recovery(state).recover(task, config(), RuntimeManifest.disabled(),
                    10_000, lease("owner", "lease", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.ARTIFACT_INTEGRITY_FAILURE,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, result).status());
        }
    }

    @Test
    void missingArtifactFailsClosedAsUnavailable() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "protected-rule");
        String artifactId;
        CanonicalId task;
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seedRecoverable(state);
            task = seed.task();
            artifactId = state.r1aDurable.findManifestByTask(task).orElseThrow()
                    .entries().stream().filter(entry -> entry.artifactId() != null)
                    .findFirst().orElseThrow().artifactId();
        }
        try (DatabaseManager manager = new DatabaseManager(
                workspace.resolve(".paicli/state"))) {
            try (var control = manager.getConnection().createStatement()) {
                control.execute("PRAGMA foreign_keys=OFF");
            }
            try (var damage = manager.getConnection().prepareStatement(
                    "DELETE FROM protected_instruction_artifacts WHERE artifact_id=?")) {
                damage.setString(1, artifactId);
                assertEquals(1, damage.executeUpdate());
            }
        }
        try (StateStore state = new StateStore(workspace)) {
            var result = recovery(state).recover(task, config(), RuntimeManifest.disabled(),
                    10_000, lease("owner", "lease", AT.plusSeconds(20)));
            assertEquals(TaskRecoveryService.RecoveryStatus.ARTIFACT_UNAVAILABLE,
                    assertInstanceOf(TaskRecoveryService.Rejected.class, result).status());
        }
    }

    private Seed seedRecoverable(StateStore state) throws Exception {
        return seed(state, workspace, true);
    }

    private static void setCanonicalTaskStatus(Path root,CanonicalId task,String status)
            throws Exception {
        try(DatabaseManager manager=new DatabaseManager(root.resolve(".paicli/state"));
                var statement=manager.getConnection().prepareStatement(
                        "UPDATE task_identities SET status=?,terminal_at=? WHERE task_id=?")){
            statement.setString(1,status);
            statement.setString(2,Set.of("CREATED","RUNNING").contains(status)
                    ?null:AT.plusSeconds(10).toString());
            statement.setString(3,task.value());
            assertEquals(1,statement.executeUpdate());
        }
    }

    private Seed seedRecoverable(StateStore state,boolean planEnabled)throws Exception{
        return seed(state,workspace,true,planEnabled);
    }

    private Seed seedRecoverable(StateStore state, Path root) throws Exception {
        return seed(state, root, true);
    }

    private Seed seed(StateStore state, boolean checkpoint) throws Exception {
        return seed(state, workspace, checkpoint);
    }

    private Seed seed(StateStore state, Path root, boolean checkpoint) throws Exception {
        return seed(state,root,checkpoint,false);
    }

    private Seed seed(StateStore state,Path root,boolean checkpoint,boolean planEnabled)
            throws Exception{
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        InstructionManifestRuntime manifests = new InstructionManifestRuntime(state, root);
        if (planEnabled) manifests = manifests.strictPlanAuthorityForTesting();
        var admission = manifests.admitRoot(
                task, "goal", 10_000, config(planEnabled), RuntimeManifest.disabled(), AT);
        if (checkpoint) {
            state.checkpoints.save(task.value(), 2, "RUNNING",
                    List.of(AgentMessage.userGoal("goal")));
        }
        return new Seed(task, admission.threadId(), admission.initialRun(),
                admission.instructions().manifest().id());
    }

    private TaskRecoveryService recovery(StateStore state) {
        return new TaskRecoveryService(state,
                new InstructionManifestRuntime(state, workspace)
                        .strictPlanAuthorityForTesting());
    }

    private Config config() {
        return config(false);
    }

    private Config config(boolean planEnabled) {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, planEnabled, true, false);
    }

    private static CanonicalId approvePlan(StateStore state,Seed seed,
            ExecutionAuthoritySnapshot initial)throws Exception{
        CanonicalId planId=CanonicalId.newId(EntityFamily.PLAN);
        var selector=PlanToolRequirementPolicy.normalize("read_file",JSON.readTree(
                "{\"kind\":\"EXACT_PATH\",\"path\":\"README.md\"}"));
        ToolRequirement requirement=new ToolRequirement("inspect-readme",0,"read_file",
                EffectClass.READ,selector,1,1,FreshnessPolicy.RESUME_REUSE_ONLY);
        StepDefinition step=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"Inspect","Inspect code",
                CompletionRuleType.ALL_REQUIRED_EVIDENCE,List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ,List.of(requirement),"b".repeat(64));
        Revision candidate=new Revision(planId,seed.task(),1,"goal","accept","","",
                "verify","0".repeat(64),"USER","test",seed.initialRun().id(),AT,
                List.of(step),List.of(),List.of(),Revision.CANONICAL_SCHEMA_V3);
        Revision revision=new Revision(planId,seed.task(),1,"goal","accept","","",
                "verify",PlanCanonicalizer.hash(candidate),"USER","test",
                seed.initialRun().id(),AT,List.of(step),List.of(),List.of(),
                Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,
                state.planAuthorities.propose(initial,AT.plusSeconds(1),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(
                initial,AT.plusSeconds(2),planId,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                initial,AT.plusSeconds(3),planId,1,0,"approval-1","user","v1",null).status());
        return planId;
    }

    /** Seeds an otherwise recovery-eligible approved V2 graph without using a production seam. */
    private CanonicalId seedApprovedV2(Seed seed) throws Exception {
        CanonicalId planId=CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition step=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"Inspect","Inspect code",
                CompletionRuleType.ALL_REQUIRED_EVIDENCE,List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ,"b".repeat(64));
        Revision draft=new Revision(planId,seed.task(),1,"goal","accept","","","verify",
                "0".repeat(64),"USER","legacy-v2-fixture",seed.initialRun().id(),AT,
                List.of(step),List.of(),List.of(),Revision.CANONICAL_SCHEMA_V2);
        Revision revision=new Revision(planId,seed.task(),1,draft.goal(),
                draft.acceptanceCriteria(),draft.assumptions(),draft.risks(),
                draft.verificationStrategy(),PlanCanonicalizer.hash(draft),draft.sourceType(),
                draft.provenance(),seed.initialRun().id(),AT,List.of(step),List.of(),List.of(),
                Revision.CANONICAL_SCHEMA_V2);
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"))){
            Connection connection=manager.getConnection();
            try(var revisionInsert=connection.prepareStatement("""
                    INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,
                        acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,
                        source_type,provenance,created_by_run_id,created_at,canonical_schema_version)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,2)
                    """)){
                int index=1;
                for(Object value:List.of(planId.value(),seed.task().value(),1,revision.goal(),
                        revision.acceptanceCriteria(),revision.assumptions(),revision.risks(),
                        revision.verificationStrategy(),revision.canonicalHash(),
                        revision.sourceType(),revision.provenance(),seed.initialRun().id().value(),
                        AT.toString())) revisionInsert.setObject(index++,value);
                revisionInsert.executeUpdate();
            }
            try(var stepInsert=connection.prepareStatement("""
                    INSERT INTO plan_step_definitions(step_revision_id,logical_step_id,
                        plan_revision_id,task_id,step_order,title,definition,canonical_hash,
                        completion_rule,required_evidence_kinds,effect_class)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)){
                int index=1;
                for(Object value:List.of(step.stepRevisionId().value(),step.logicalStepId().value(),
                        planId.value(),seed.task().value(),0,step.title(),step.definition(),
                        step.canonicalHash(),step.completionRule().name(),"TOOL_CALL",
                        step.effectClass().name())) stepInsert.setObject(index++,value);
                stepInsert.executeUpdate();
            }
            try(var statement=connection.createStatement()){
                statement.executeUpdate("""
                        INSERT INTO plan_revision_lifecycle(plan_revision_id,task_id,state,
                            cas_version,updated_by_run_id,updated_at)
                        VALUES('%s','%s','APPROVED',2,'%s','%s')
                        """.formatted(planId.value(),seed.task().value(),
                        seed.initialRun().id().value(),AT.plusSeconds(3)));
                statement.executeUpdate("""
                        INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                            approved_by,approved_by_run_id,policy_version,decided_at)
                        VALUES('legacy-v2-approval','%s','%s','APPROVED','user','%s','v2','%s')
                        """.formatted(seed.task().value(),planId.value(),
                        seed.initialRun().id().value(),AT.plusSeconds(3)));
                statement.executeUpdate("""
                        INSERT INTO task_plan_authorities(task_id,active_plan_revision_id,
                            active_revision_state,active_approval_id,active_approval_decision,
                            activated_by_run_id,pointer_version,activated_at)
                        VALUES('%s','%s','APPROVED','legacy-v2-approval','APPROVED','%s',1,'%s')
                        """.formatted(seed.task().value(),planId.value(),
                        seed.initialRun().id().value(),AT.plusSeconds(3)));
                statement.executeUpdate("""
                        INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                            approval_id,approval_decision,plan_hash,bound_at)
                        VALUES('legacy-v2-binding','%s','%s','%s','legacy-v2-approval',
                            'APPROVED','%s','%s')
                        """.formatted(seed.task().value(),seed.initialRun().id().value(),
                        planId.value(),revision.canonicalHash(),AT.plusSeconds(3)));
            }
            var initializeSteps=PlanExecutionStore.class.getDeclaredMethod(
                    "initializeForApprovedBinding",Connection.class,CanonicalId.class,
                    CanonicalId.class,CanonicalId.class,Instant.class,String.class);
            initializeSteps.setAccessible(true);
            initializeSteps.invoke(null,connection,seed.task(),planId,seed.initialRun().id(),
                    AT.plusSeconds(3),"LEGACY_V2_RECOVERY_FIXTURE");
            var initializeRules=PlanCompletionStore.class.getDeclaredMethod("initializeRules",
                    Connection.class,CanonicalId.class,CanonicalId.class,CanonicalId.class,
                    Instant.class);
            initializeRules.setAccessible(true);
            initializeRules.invoke(null,connection,seed.task(),planId,seed.initialRun().id(),
                    AT.plusSeconds(3));
        }
        return planId;
    }

    private long count(String table)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement();
                var result=statement.executeQuery("SELECT count(*) FROM "+table)){
            result.next();return result.getLong(1);
        }
    }

    private static TaskRecoveryService.LeaseRequest lease(
            String owner, String token, Instant at) {
        return new TaskRecoveryService.LeaseRequest(owner, token, at,
                at.plusSeconds(30), Duration.ZERO);
    }

    private record Seed(CanonicalId task, CanonicalId thread,
                        com.paicli.state.R1aDurableModels.DurableRun initialRun,
                        String manifestId) { }
}
