package com.paicli.interactive.view;

import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.DatabaseManager;
import com.paicli.state.InstructionManifestCandidate;
import com.paicli.state.ProtectedInstructionArtifact;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.RootAdmissionTransaction;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels;
import com.paicli.config.ConfigLoader;
import com.paicli.config.Config;
import com.paicli.config.ProviderConfigStore;
import com.paicli.provider.ProviderHealthService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RecoveryPreflightValidator;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.state.ProviderCallTelemetryStore;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanCompletionModels;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class R2InteractiveSessionViewModelReaderTest {
    @TempDir Path workspace;

    @Test void repeatedAndCrossProcessReadsProduceStableDurableFacts() throws Exception {
        InteractiveSession session = session();
        InteractiveSessionViewModel first;
        InteractiveSessionViewModel second;
        try (StateStore state = new StateStore(workspace)) {
            first = state.viewModels.read(session.snapshot(), null);
            second = state.viewModels.read(session.snapshot(), null);
        }
        InteractiveSessionViewModel crossProcess;
        try (StateStore reopened = new StateStore(workspace)) {
            crossProcess = reopened.viewModels.read(session.snapshot(), null);
        }
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertEquals(first.task().state(), second.task().state());
        assertEquals(first.task().state(), crossProcess.task().state());
        assertEquals(ViewFact.State.ABSENT, first.task().state());
        assertEquals(ViewFact.State.NOT_APPLICABLE, first.plan().state());
        assertEquals(OperationalStatusView.Status.READY,
                first.operationalStatus().primary());
        assertEquals(first.operationalStatus().primary(),
                crossProcess.operationalStatus().primary());
        assertEquals(first.workspace(), crossProcess.workspace());
    }

    @Test void terminalTaskWithUnapprovedRevisionDoesNotRemainWaitingForApproval()
            throws Exception {
        RootAdmissionTransaction.Request admitted = rootRequest(
                StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
        InteractiveSession session = session();
        session.startTask(admitted.task().id().value(), admitted.initialRun().id().value());
        try (StateStore state = new StateStore(workspace)) {
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            Instant at = admitted.committedAt();
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(admitted.task().id(), "view-runtime",
                            admitted.initialRun().id(), "view-lease", at,
                            at.plusSeconds(60)));
            var lease = state.r1aDurable.readOwnership(admitted.task().id()).orElseThrow();
            var authority = new ExecutionAuthoritySnapshot(admitted.task().id(),
                    admitted.initialRun().id(), lease.ownerInstanceId(), lease.leaseToken(),
                    lease.fencingToken(), lease.casVersion());
            var step = new StructuredPlanModels.StepDefinition(
                    CanonicalId.newId(EntityFamily.PLAN_STEP),
                    CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Inspect", "List files",
                    PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                    List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                    PlanCompletionModels.EffectClass.READ,
                    List.of(exactReadRequirement("view-model-read")), "a".repeat(64));
            var draft = new StructuredPlanModels.Revision(
                    CanonicalId.newId(EntityFamily.PLAN), admitted.task().id(), 1, "goal",
                    "listed", "none", "none", "tool evidence", "0".repeat(64),
                    "MODEL", "test", admitted.initialRun().id(), at, List.of(step),
                    List.of(), List.of(), StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
            var revision = new StructuredPlanModels.Revision(draft.planRevisionId(),
                    draft.taskId(), draft.revisionNumber(), draft.goal(),
                    draft.acceptanceCriteria(), draft.assumptions(), draft.risks(),
                    draft.verificationStrategy(), PlanCanonicalizer.hash(draft),
                    draft.sourceType(), draft.provenance(), draft.createdByRunId(),
                    draft.createdAt(), draft.steps(), draft.dependencies(), draft.lineage(),
                    StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority, at.plusSeconds(1), revision).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.validateRevision(authority, at.plusSeconds(2),
                            revision.planRevisionId(), 0).status());
            assertEquals(com.paicli.state.R1aStoreResults.FencedMutationResult.SUCCESS,
                    state.fencedMutations.terminalize(authority, at.plusSeconds(3),
                            revision.planRevisionId().value(), false,
                            "authority promotion failed"));
            session.finishTask(admitted.task().id().value());

            InteractiveSessionViewModel model = state.viewModels.read(
                    session.snapshot(), null);
            assertEquals("FAILED", model.task().value().status());
            assertEquals("FAILED", model.run().value().status());
            assertEquals(OperationalStatusView.Status.READY,
                    model.operationalStatus().primary());
            assertEquals("paicli [ready]> ",
                    new InteractiveViewModelRenderer().prompt(model));
            assertFalse(model.operationalStatus().allowedActionHints()
                    .contains("approve"));
        }
    }

    @Test void mismatchedRuntimePreviewIsNeverMergedAndOnlyReducesHints()
            throws Exception {
        InteractiveSession session = session();
        InteractiveRuntimeView runtime = new InteractiveRuntimeView();
        runtime.taskStarted("tsk_preview_only", "run_preview_only");
        try (StateStore state = new StateStore(workspace)) {
            InteractiveSessionViewModel model = state.viewModels.read(
                    session.snapshot(), runtime.snapshot());
            assertTrue(model.warnings().contains("STALE_PREVIEW"));
            assertEquals(ViewFact.State.ABSENT, model.task().state());
            assertTrue(Set.of("/status", "/doctor", "/exit").containsAll(
                    model.operationalStatus().allowedActionHints()));
            assertFalse(model.operationalStatus().allowedActionHints()
                    .contains("submit task"));
        }
    }

    @Test void concurrentHeadCommitIsObservedEntirelyBeforeOrAfter() throws Exception {
        RootAdmissionTransaction.Request admitted;
        try (StateStore state = new StateStore(workspace)) {
            admitted = rootRequest();
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
        }
        InteractiveSession session = session();
        session.startTask(admitted.task().id().value(), admitted.initialRun().id().value());
        Path stateDirectory = workspace.resolve(".paicli").resolve("state");
        try (StateStore reader = new StateStore(workspace);
             DatabaseManager writer = new DatabaseManager(stateDirectory)) {
            writer.getConnection().setAutoCommit(false);
            try (Statement statement = writer.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE thread_heads SET pointer_version=1 WHERE thread_id='"
                        + admitted.thread().id().value() + "'");
            }
            InteractiveSessionViewModel before = reader.viewModels.read(
                    session.snapshot(), null);
            writer.getConnection().commit();
            writer.getConnection().setAutoCommit(true);
            InteractiveSessionViewModel after = reader.viewModels.read(
                    session.snapshot(), null);

            assertTrue(before.thread().isPresent(), () -> diagnostic("before", before));
            assertTrue(after.thread().isPresent(), () -> diagnostic("after", after));
            assertEquals(0, before.thread().value().headVersion());
            assertEquals(1, after.thread().value().headVersion());
            assertEquals(before.task().value().taskId(), after.task().value().taskId());
            assertEquals(before.run().value().runId(), after.run().value().runId());
            assertEquals(before.generation().value().generationId(),
                    after.generation().value().generationId());
            assertFalse(before.toString().contains("view-secret"));
            assertFalse(after.toString().contains("sensitive raw goal"));
        }
    }

    @Test void matchingPreviewRequiresWorkspaceThreadTaskRunAndGeneration()
            throws Exception {
        RootAdmissionTransaction.Request admitted=rootRequest();
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            InteractiveSession session=session();
            session.bindThread(new SessionBinding(SessionBinding.State.BOUND_ACTIVE,
                    admitted.thread().id(),admitted.task().id(),admitted.task().status(),
                    Instant.parse("2026-07-20T00:00:01Z")));
            session.startTask(admitted.task().id().value(),admitted.initialRun().id().value());
            InteractiveRuntimeView runtime=new InteractiveRuntimeView();
            runtime.taskStarted(SavedSessionService.workspaceIdentity(workspace),
                    admitted.thread().id().value(),admitted.task().id().value(),
                    admitted.initialRun().id().value(),admitted.generation().id().value());

            InteractiveSessionViewModel matching=state.viewModels.read(
                    session.snapshot(),runtime.snapshot());
            assertFalse(matching.warnings().contains("STALE_PREVIEW"));
            runtime.confirmationPending(true);
            InteractiveSessionViewModel confirmation=state.viewModels.read(
                    session.snapshot(),runtime.snapshot());
            assertEquals(OperationalStatusView.Status.WAITING_FOR_CONFIRMATION,
                    confirmation.operationalStatus().primary(),
                    () -> diagnostic("confirmation", confirmation));
            assertTrue(confirmation.operationalStatus().allowedActionHints()
                    .contains("confirm"));

            runtime.taskFinished(admitted.task().id().value(),
                    admitted.initialRun().id().value(),"FAILED");
            runtime.taskStarted(SavedSessionService.workspaceIdentity(workspace),
                    admitted.thread().id().value(),admitted.task().id().value(),
                    admitted.initialRun().id().value(),CanonicalId.newId(
                            EntityFamily.GENERATION).value());
            InteractiveSessionViewModel stale=state.viewModels.read(
                    session.snapshot(),runtime.snapshot());
            assertTrue(stale.warnings().contains("STALE_PREVIEW"));
        }
    }

    @Test void telemetrySqlFailurePreservesR1SnapshotAndOnlyDegradesProvider()
            throws Exception {
        RootAdmissionTransaction.Request admitted=rootRequest();
        InteractiveSession session=session();
        session.startTask(admitted.task().id().value(),admitted.initialRun().id().value());
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            try(DatabaseManager external=new DatabaseManager(
                    workspace.resolve(".paicli").resolve("state"));
                Statement statement=external.getConnection().createStatement()){
                statement.execute("DROP TABLE provider_call_telemetry");
            }
            InteractiveSessionViewModel model=state.viewModels.read(session.snapshot(),null);
            assertTrue(model.task().isPresent());
            assertTrue(model.run().isPresent());
            assertTrue(model.generation().isPresent());
            assertEquals(ProviderView.AvailabilityState.UNAVAILABLE,
                    model.provider().availabilityState());
            assertTrue(model.warnings().contains("PROVIDER_TELEMETRY_UNAVAILABLE"));
            assertNotEquals(OperationalStatusView.Status.BLOCKED_INTEGRITY,
                    model.operationalStatus().primary());
        }
    }

    @Test void recoveryPeekAndMutationUseTheSamePreflightWithoutCreatingResumeRun()
            throws Exception {
        RootAdmissionTransaction.Request admitted=rootRequest();
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            var config=new ConfigLoader(workspace).load();
            var runtime=new RuntimeManifestHasher(workspace).compute(config);
            Instant at=Instant.parse("2026-07-20T00:01:00Z");
            RecoveryPreflightValidator.Result preflight=state.recoveryPreflight.validate(
                    admitted.task().id(),config,runtime,config.maxProjectInstructionChars(),at);
            var peek=state.authorityPeeks.peekRecoveryAuthority(admitted.task().id(),at);
            int before=state.r1aDurable.findRunsByTask(admitted.task().id()).size();
            var recovery=new TaskRecoveryService(state,
                    new InstructionManifestRuntime(state,workspace)).recover(
                    admitted.task().id(),config,runtime,config.maxProjectInstructionChars(),
                    new TaskRecoveryService.LeaseRequest("test-owner","test-lease",at,
                            at.plusSeconds(60),Duration.ZERO));
            assertEquals(RecoveryPreflightValidator.Eligibility.NOT_ELIGIBLE,
                    preflight.eligibility());
            assertNotNull(peek.value());
            assertFalse(peek.value().eligible());
            TaskRecoveryService.Rejected rejected=assertInstanceOf(
                    TaskRecoveryService.Rejected.class,recovery);
            assertEquals(preflight.status(),rejected.status());
            assertEquals(before,state.r1aDurable.findRunsByTask(admitted.task().id()).size());
        }
    }

    @Test void historicalTaskProjectionNeverCarriesCurrentHeadIdentity() throws Exception {
        RootAdmissionTransaction.Request current=rootRequest();
        RootAdmissionTransaction.Request historical=rootRequest();
        InteractiveSession session=session();
        session.startTask(current.task().id().value(),current.initialRun().id().value());
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(current));
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(historical));
            InteractiveSessionViewModel selected=state.readOnlySnapshot(()->
                    state.viewModels.readTaskInSnapshot(session.snapshot(),historical.task().id()));
            assertEquals(historical.task().id().value(),selected.task().value().taskId());
            assertEquals(historical.initialRun().id().value(),selected.run().value().runId());
            assertNotEquals(current.task().id().value(),selected.task().value().taskId());
            assertTrue(selected.warnings().contains("HISTORICAL_PROJECTION"));
            assertEquals(ViewFact.State.NOT_APPLICABLE,selected.recovery().state());
            assertEquals(ViewFact.State.NOT_APPLICABLE,selected.followUp().state());
        }
    }

    @Test void malformedTelemetryTimestampIsUnavailableWithoutLosingR1Facts()
            throws Exception {
        RootAdmissionTransaction.Request admitted=rootRequest();
        InteractiveSession session=session();
        session.startTask(admitted.task().id().value(),admitted.initialRun().id().value());
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            String correlation=java.util.UUID.randomUUID().toString();
            assertTrue(state.providerTelemetry.recordStarted(
                    new ProviderCallTelemetryStore.CallStart(correlation,"ollama","model",
                            ProviderCallTelemetryStore.OperationKind.CHAT,
                            new ProviderCallContext(session.snapshot().sessionId(),
                                    admitted.task().id().value(),admitted.initialRun().id().value(),
                                    admitted.generation().id().value()),Instant.now())));
            try(DatabaseManager external=new DatabaseManager(
                    workspace.resolve(".paicli").resolve("state"));
                Statement statement=external.getConnection().createStatement()){
                statement.execute("PRAGMA ignore_check_constraints=ON");
                statement.executeUpdate("UPDATE provider_call_telemetry SET started_at='bad' "
                        +"WHERE correlation_id='"+correlation+"'");
            }
            InteractiveSessionViewModel model=state.viewModels.read(session.snapshot(),null);
            assertTrue(model.task().isPresent());
            assertTrue(model.run().isPresent());
            assertEquals(ProviderView.AvailabilityState.UNAVAILABLE,
                    model.provider().availabilityState());
            assertNotEquals(OperationalStatusView.Status.BLOCKED_INTEGRITY,
                    model.operationalStatus().primary());
        }
    }

    @Test void failedPlanningCallIsProjectedWithCountsAndTypedCategory()
            throws Exception {
        RootAdmissionTransaction.Request admitted=rootRequest();
        InteractiveSession session=session();
        session.startTask(admitted.task().id().value(),admitted.initialRun().id().value());
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(admitted));
            String correlation=java.util.UUID.randomUUID().toString();
            Instant started=Instant.now();
            assertTrue(state.providerTelemetry.recordStarted(
                    new ProviderCallTelemetryStore.CallStart(correlation,"ollama","model",
                            ProviderCallTelemetryStore.OperationKind.PLAN,
                            new ProviderCallContext(session.snapshot().sessionId(),
                                    admitted.task().id().value(),admitted.initialRun().id().value(),
                                    admitted.generation().id().value()),started)));
            assertTrue(state.providerTelemetry.finalizeCall(
                    new ProviderCallTelemetryStore.CallFinish(correlation,
                            ProviderCallTelemetryStore.Lifecycle.FAILED,
                            started.plusMillis(25),25,"PROVIDER_ERROR",0,
                            ProviderCallObservation.notExposed().withError(
                                    "PROVIDER_TIMEOUT",false),null,
                            "ptc:"+correlation)));

            InteractiveSessionViewModel model=state.viewModels.read(
                    session.snapshot(),null);
            assertEquals(ProviderView.LastCallState.LAST_CALL_FAILED,
                    model.provider().lastCallState());
            assertEquals("PLAN",model.provider().lastOperationKind());
            assertEquals("PROVIDER_ERROR",model.provider().lastOutcome());
            assertEquals("PROVIDER_TIMEOUT",model.provider().lastErrorCategory());
            assertEquals(1,model.provider().sessionCallCount());
            assertEquals(1,model.provider().taskCallCount());
        }
    }

    @Test void healthObservationSurvivesPlanChatToolContinuationAndFinalSynthesisCalls()
            throws Exception {
        InteractiveSession session=session();
        Config configured=Config.defaults().withProvider("ollama","model",
                "http://127.0.0.1:11434",null);
        String fingerprint=ProviderHealthService.observationFingerprint(configured);
        session.changeProvider("ollama","model","session",fingerprint);
        try(StateStore state=new StateStore(workspace)){
            Instant healthAt=Instant.now().minusSeconds(30);
            recordCall(state,session,"health-retained",ProviderCallTelemetryStore.OperationKind.HEALTH,
                    healthAt,"HEALTHY",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                    fingerprint);
            recordCall(state,session,"plan-after-health",ProviderCallTelemetryStore.OperationKind.PLAN,
                    healthAt.plusSeconds(5),"FINAL",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);
            recordCall(state,session,"chat-after-health-1",ProviderCallTelemetryStore.OperationKind.CHAT,
                    healthAt.plusSeconds(10),"TOOL_CALL",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);
            recordCall(state,session,"chat-after-health-2",ProviderCallTelemetryStore.OperationKind.CHAT,
                    healthAt.plusSeconds(15),"TOOL_CALL",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);
            recordCall(state,session,"chat-after-health-3",ProviderCallTelemetryStore.OperationKind.CHAT,
                    healthAt.plusSeconds(20),"FINAL",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);

            InteractiveSessionViewModel model=state.viewModels.read(session.snapshot(),null);
            assertEquals(ProviderView.ReadinessState.HEALTHY,
                    model.provider().readinessState());
            assertEquals("CHAT",model.provider().lastOperationKind());
            assertEquals(healthAt.plusMillis(1),model.provider().healthObservedAt());
            assertEquals(5,model.provider().sessionCallCount());
        }
    }

    @Test void expiredHealthIsStaleAndNewSessionWithoutHealthIsNotChecked()
            throws Exception {
        InteractiveSession session=session();
        try(StateStore state=new StateStore(workspace)){
            Instant old=Instant.now().minus(Duration.ofMinutes(6));
            recordCall(state,session,"health-stale",ProviderCallTelemetryStore.OperationKind.HEALTH,
                    old,"HEALTHY",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);
            assertEquals(ProviderView.ReadinessState.STALE,
                    state.viewModels.read(session.snapshot(),null).provider().readinessState());

            InteractiveSession fresh=new InteractiveSession("session-without-health",workspace,
                    "ollama","model","on-request",InteractiveSession.TerminalMode.PLAIN,
                    Instant.now());
            fresh.start();
            assertEquals(ProviderView.ReadinessState.READINESS_UNKNOWN,
                    state.viewModels.read(fresh.snapshot(),null).provider().readinessState());
        }
    }

    @Test void providerOrModelChangeDoesNotReuseAnotherConfigurationHealth()
            throws Exception {
        InteractiveSession session=session();
        try(StateStore state=new StateStore(workspace)){
            Instant at=Instant.now().minusSeconds(10);
            recordCall(state,session,"health-old-config",ProviderCallTelemetryStore.OperationKind.HEALTH,
                    at,"HEALTHY",ProviderCallTelemetryStore.Lifecycle.SUCCEEDED);
            session.changeProvider("ollama","different-model","session");
            assertEquals(ProviderView.ReadinessState.READINESS_UNKNOWN,
                    state.viewModels.read(session.snapshot(),null).provider().readinessState());
        }
    }

    @Test void healthWithoutCurrentSessionConfigurationFingerprintFailsClosed()
            throws Exception {
        InteractiveSession session=new InteractiveSession("session-missing-fingerprint",workspace,
                "ollama","model","on-request",InteractiveSession.TerminalMode.PLAIN,
                Instant.now());
        session.start();
        Config checked=Config.defaults().withProvider("ollama","model",
                "http://127.0.0.1:11434",null);
        try(StateStore state=new StateStore(workspace)){
            Instant at=Instant.now().minusSeconds(10);
            recordCall(state,session,"health-without-current-identity",
                    ProviderCallTelemetryStore.OperationKind.HEALTH,at,"HEALTHY",
                    ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                    ProviderHealthService.observationFingerprint(checked));
            assertEquals(ProviderView.ReadinessState.READINESS_UNKNOWN,
                    state.viewModels.read(session.snapshot(),null).provider().readinessState());
        }
    }

    @Test void endpointOrKeyEnvironmentChangeInvalidatesOldHealthFingerprint()
            throws Exception {
        Config checked=Config.defaults().withProvider("openai","model",
                "https://api.example.test","KEY_ENV_A");
        InteractiveSession session=new InteractiveSession("session-config-change",workspace,
                "openai","model","on-request",InteractiveSession.TerminalMode.PLAIN,
                Instant.now());
        session.start();
        session.changeProvider("openai","model","session",
                ProviderHealthService.observationFingerprint(checked));
        try(StateStore state=new StateStore(workspace)){
            Instant at=Instant.now().minusSeconds(10);
            recordCall(state,session,"health-fingerprint",
                    ProviderCallTelemetryStore.OperationKind.HEALTH,at,"HEALTHY",
                    ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                    ProviderHealthService.observationFingerprint(checked));
            assertEquals(ProviderView.ReadinessState.HEALTHY,
                    state.viewModels.read(session.snapshot(),null).provider().readinessState());

            Config changed=checked.withProvider("openai","model",
                    "https://gateway.example.test","KEY_ENV_B");
            session.changeProvider("openai","model","session",
                    ProviderHealthService.observationFingerprint(changed));
            assertEquals(ProviderView.ReadinessState.READINESS_UNKNOWN,
                    state.viewModels.read(session.snapshot(),null).provider().readinessState());
        }
    }

    private void recordCall(StateStore state,InteractiveSession session,String suffix,
            ProviderCallTelemetryStore.OperationKind operation,Instant started,String outcome,
            ProviderCallTelemetryStore.Lifecycle lifecycle){
        recordCall(state,session,suffix,operation,started,outcome,lifecycle,null);
    }

    private void recordCall(StateStore state,InteractiveSession session,String suffix,
            ProviderCallTelemetryStore.OperationKind operation,Instant started,String outcome,
            ProviderCallTelemetryStore.Lifecycle lifecycle,String structuralHash){
        if (operation == ProviderCallTelemetryStore.OperationKind.HEALTH
                && structuralHash == null) {
            structuralHash = session.snapshot().providerConfigurationFingerprint();
        }
        String correlation=java.util.UUID.nameUUIDFromBytes((suffix+System.nanoTime()).getBytes())
                .toString();
        assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                correlation,session.snapshot().provider(),session.snapshot().model(),operation,
                new ProviderCallContext(session.snapshot().sessionId(),null,null,null),started)));
        assertTrue(state.providerTelemetry.finalizeCall(new ProviderCallTelemetryStore.CallFinish(
                correlation,lifecycle,started.plusMillis(1),1,outcome,0,
                ProviderCallObservation.notExposed(),structuralHash,"ptc:"+correlation)));
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-r2-reader", workspace,
                "ollama", "model", "on-request",
                InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-20T00:00:00Z"));
        session.start();
        Config config=Config.defaults().withProvider("ollama","model",
                "http://localhost:11434",null);
        session.changeProvider("ollama","model","session",
                ProviderHealthService.observationFingerprint(config));
        return session;
    }

    private RootAdmissionTransaction.Request rootRequest() {
        return rootRequest(StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
    }

    private RootAdmissionTransaction.Request rootRequest(
            StructuredPlanModels.PlanApplicability applicability) {
        Instant at = Instant.parse("2026-07-20T00:00:00Z");
        CanonicalId thread = CanonicalId.newId(EntityFamily.THREAD);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        CanonicalId generation = CanonicalId.newId(EntityFamily.GENERATION);
        String manifest = "manifest-" + java.util.UUID.randomUUID();
        ProtectedInstructionArtifact artifact = ProtectedInstructionArtifact.inlineUtf8(
                "artifact-" + java.util.UUID.randomUUID(), "frozen", "v1", "TASK_AUTHORITY", at);
        var entry = new InstructionManifestCandidate.Entry(
                "entry-" + java.util.UUID.randomUUID(), "PROJECT_FILE", "AGENTS.md",
                "WORKSPACE", InstructionManifestCandidate.Status.APPLIED, 0,
                artifact.contentHash(), artifact.id(), "UTF8", null, null);
        var candidate = new InstructionManifestCandidate(1,
                "root-paicli-agents-v1", List.of(entry));
        String hash = candidate.canonicalHash();
        WorkThread workThread = new WorkThread(thread,
                SavedSessionService.workspaceIdentity(workspace), "sensitive raw goal",
                "OPEN", at, at);
        DurableTask durableTask = new DurableTask(task, thread, null,
                "Authorization: Bearer view-secret", "CREATED", null, null, at, null);
        DurableRun durableRun = new DurableRun(run, task, thread, "INITIAL", null,
                manifest, hash, at, null);
        TaskContextGeneration context = new TaskContextGeneration(generation, task, null,
                run, manifest, hash, "COMMITTED", "{}", at, at);
        return new RootAdmissionTransaction.Request(workThread, durableTask, manifest,
                candidate, List.of(artifact), durableRun,
                context, new TaskContextPointer(task, generation, run, 0),
                new RootAdmissionTransaction.RuntimeTask(task.value(),
                        "Authorization: Bearer view-secret",
                        "mock", "model", "deny", 3, "RUNNING"),
                new RootAdmissionTransaction.RuntimeRun(run.value(), task.value(),
                        "INITIAL", "PLANNING", "", 1, 0,
                        "skills-disabled", "hooks-disabled"),
                applicability, at);
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement(String id) {
        String json = "{\"path\":\"AGENTS.md\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                com.paicli.state.PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement(id, 0, "read_file",
                PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private static String diagnostic(String label,InteractiveSessionViewModel model){
        return label+" thread="+model.thread().state()
                +" integrity="+model.durableAuthorityIntegrity().state()
                +":"+model.durableAuthorityIntegrity().diagnosticRef()
                +" warnings="+model.warnings();
    }
}
