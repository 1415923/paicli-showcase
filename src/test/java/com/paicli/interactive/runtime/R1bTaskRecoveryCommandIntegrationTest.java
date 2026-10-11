package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.runtime.TaskExecutionAuthority;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.WorkspaceCandidateDigestTestAccess;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.CodingRunStore;
import com.paicli.state.TaskStore;
import com.paicli.state.StateStore;
import com.paicli.state.VerificationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class R1bTaskRecoveryCommandIntegrationTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    @TempDir Path workspace;

    @Test
    void interruptedThinkingToolContinuationIsRejectedBeforeUnsafeResume()throws Exception{
        Config config=config();StateStore state=new StateStore(workspace);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,
                "thinking continuation",10_000,config,
                new RuntimeManifestHasher(workspace).compute(config),AT);
        String toolCallJson="""
                {"id":"call-private","type":"function",
                 "function":{"name":"list_files","arguments":"{}"},
                 "x_paicli_private_continuation":true}
                """;
        state.checkpoints.save(task.value(),0,"RUNNING",List.of(
                AgentMessage.userGoal("inspect")));
        state.events.append(task.value(),1,AgentMessage.actionWithToolCall(
                "inspect workspace","call-private",toolCallJson));
        InteractiveRuntimeAdapter adapter=new InteractiveRuntimeAdapter(
                workspace,new ProductionInteractiveTaskExecutor(workspace,()->config),
                ignored->{},new InteractiveRuntimeView(),state);
        try{
            InteractiveSession session=session();adapter.openSession(session);
            new SavedSessionService(state,workspace,Clock.fixed(AT.plusSeconds(1),ZoneOffset.UTC))
                    .bind(session,root.threadId().value());
            assertEquals("PREPARING",adapter.recoverTask(task.value()).code());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals("PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE",
                    adapter.lastResult().orElseThrow().code());
            assertTrue(state.r1aDurable.findRunsByTask(task).stream()
                    .noneMatch(run->"RESUME".equals(run.kind())),
                    "a private continuation must fail before admitting a partial resume Run");
        }finally{adapter.close();}
    }

    @Test
    void explicitRecoveryUsesR1bAServiceForSameTaskAndNewResumeRun()
            throws Exception {
        Config config = config();
        StateStore state = new StateStore(workspace);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                task, "recover through command", 10_000, config,
                new RuntimeManifestHasher(workspace).compute(config), AT);
        state.checkpoints.save(task.value(), 1, "RUNNING",
                List.of(AgentMessage.userGoal("recover through command")));
        String originalManifest = root.instructions().manifest().id();
        CanonicalId originalRun = root.initialRun().id();

        InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, new ProductionInteractiveTaskExecutor(workspace, () -> config),
                ignored -> { }, new InteractiveRuntimeView(), state);
        try {
            InteractiveSession session = session();
            adapter.openSession(session);
            new SavedSessionService(state, workspace,
                    Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC))
                    .bind(session, root.threadId().value());

            assertEquals("PREPARING", adapter.recoverTask(task.value()).code());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));

            var runs = state.r1aDurable.findRunsByTask(task);
            assertEquals(2, runs.size());
            var resume = runs.stream().filter(run -> "RESUME".equals(run.kind()))
                    .findFirst().orElseThrow();
            assertEquals(task, resume.taskId());
            assertEquals(originalRun, resume.resumedFromRunId());
            assertEquals(originalManifest, resume.manifestId());
            assertEquals(resume.id(),state.r1aDurable.readOwnership(task)
                    .orElseThrow().ownerRunId());
            assertEquals(task, state.r1aDurable
                    .readThreadHead(root.threadId()).orElseThrow().headTaskId());
            assertEquals(1, state.r1aDurable
                    .findManifestByTask(task).stream().count());
        } finally {
            adapter.close();
        }
    }

    @Test
    void acceptedFinalCrashResumesSameRunWithoutReenteringAgentLoop()throws Exception{
        Config config=config().withVerification(passCommand(),true);
        StateStore state=new StateStore(workspace);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,
                "finish after accepted FINAL",10_000,config,
                new RuntimeManifestHasher(workspace).compute(config),AT);
        CanonicalId run=root.initialRun().id();
        assertEquals(CodingRunStore.PHASE_EXECUTING,
                state.codingRuns.findById(run.value()).orElseThrow().phase());
        assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(task,
                "crashed-runtime",run,"crashed-lease",AT,AT.plusSeconds(30)));
        var lease=state.r1aDurable.readOwnership(task).orElseThrow();
        FencedExecutionGuard guard=new FencedExecutionGuard(state,
                TaskExecutionAuthority.initial(lease),Duration.ofSeconds(30),
                ()->AT.plusSeconds(1));
        String privateAction="""
                {"id":"call-finished-private","type":"function",
                 "function":{"name":"list_files","arguments":"{}"},
                 "x_paicli_private_continuation":true}
                """;
        state.events.append(task.value(),0,AgentMessage.actionWithToolCall(
                "completed private continuation","call-finished-private",privateAction));
        state.events.append(task.value(),0,AgentMessage.observationForToolCall(
                "completed","call-finished-private"));
        state.checkpoints.save(task.value(),0,"RUNNING",
                List.of(AgentMessage.userGoal("finish after accepted FINAL")));
        String digest=WorkspaceCandidateDigestTestAccess.compute(workspace);
        CanonicalId occurrence=guard.admitOperationOccurrence("1","provider-final-crash",
                "PROVIDER","CHAT",FencedExecutionGuard.canonicalInputHash("input"));
        String finalText="exact accepted FINAL after crash";
        guard.acceptProviderFinal(occurrence,1,CodingRunStore.PHASE_EXECUTING,
                finalText,digest);

        AtomicInteger providerCalls=new AtomicInteger();
        ProductionInteractiveTaskExecutor executor=new ProductionInteractiveTaskExecutor(
                workspace,()->config,ignoredConfig->new com.paicli.provider.ModelProvider(){
                    @Override public boolean usesManagedAnswerEnvelope(){return true;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    return chatForOperation(operation,messages,tools);
                }
                    @Override public com.paicli.model.ModelResponse chat(
                            List<AgentMessage> messages,List<com.paicli.model.ToolDefinition> tools){
                        providerCalls.incrementAndGet();
                        return com.paicli.model.ModelResponse.finalAnswer("unexpected Provider turn");
                    }
                });
        InteractiveRuntimeAdapter adapter=new InteractiveRuntimeAdapter(workspace,executor,
                ignored->{},new InteractiveRuntimeView(),state);
        try{
            InteractiveSession session=new InteractiveSession("final-recovery-session",workspace,
                    Config.PROVIDER_MOCK,"model-a",Config.MODE_DENY,
                    InteractiveSession.TerminalMode.PLAIN,AT.plusSeconds(31),
                    "configured","configured verifier");
            session.start();adapter.openSession(session);
            new SavedSessionService(state,workspace,
                    Clock.fixed(AT.plusSeconds(31),ZoneOffset.UTC))
                    .bind(session,root.threadId().value());

            assertEquals("PREPARING",adapter.recoverTask(task.value()).code());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(10)));
            var terminal=adapter.lastResult().orElseThrow();
            assertEquals(run.value(),terminal.runId(),terminal::toString);
            assertEquals(0,providerCalls.get());
            assertEquals(0,state.providerTelemetry.taskUsage(task.value()).providerTurns());
            assertEquals(1,state.r1aDurable.findRunsByTask(task).size());
            assertTrue(state.r1aDurable.findRunsByTask(task).stream()
                    .noneMatch(value->CodingRunStore.KIND_RESUME.equals(value.kind())));
            assertEquals(CodingRunStore.PHASE_COMPLETED,
                    state.codingRuns.findById(run.value()).orElseThrow().phase());
            assertEquals(TaskStore.STATUS_COMPLETED_VERIFIED,
                    state.tasks.findById(task.value()).orElseThrow().status());

            var verification=state.verifications.findByRun(run.value()).getFirst();
            assertEquals(VerificationStore.STATUS_PASSED,verification.status());
            assertEquals(WorkspaceCandidateDigestTestAccess.kind(),
                    verification.candidateDigestKind());
            assertEquals(digest,verification.candidateDigest());
            var result=state.userResults.find(task,run).orElseThrow();
            assertNull(result.planRevisionId());
            assertEquals(finalText,result.content());
            assertTrue(state.userResults.completionReadyPlanless(task,run,result.contentHash(),
                    occurrence.value(),digest,"PASSED:"+verification.verificationId()));
            assertFalse(state.userResults.completionReadyPlanless(task,run,result.contentHash(),
                    CanonicalId.newId(EntityFamily.OCCURRENCE).value(),digest,
                    "PASSED:"+verification.verificationId()));
        }finally{adapter.close();}
    }

    @Test
    void adapterCannotTerminalizeRecoveryAfterAuthorityTakeover()throws Exception{
        Config config=config();StateStore state=new StateStore(workspace);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"recover",10_000,
                config,new RuntimeManifestHasher(workspace).compute(config),AT);
        state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("recover")));
        TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace)).recover(
                        task,config,new RuntimeManifestHasher(workspace).compute(config),10_000,
                        new TaskRecoveryService.LeaseRequest("runtime-a","lease-a",AT,
                                AT.plusSeconds(30),Duration.ZERO)));
        FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                Duration.ofSeconds(30),()->AT.plusSeconds(1));
        InteractiveRuntimeTaskExecutor executor=new InteractiveRuntimeTaskExecutor(){
            public CodingTaskResult execute(String id,
                    com.paicli.interactive.task.InteractiveTaskRequest request,StateStore store,
                    RuntimeEventBridge bridge){throw new UnsupportedOperationException();}
            public CodingTaskResult resume(String id,StateStore store,RuntimeEventBridge bridge)
                    throws Exception{
                bridge.bindExecutionGuard(guardA);
                DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),task,
                        root.threadId(),"RESUME",runtimeA.resumeRun().id(),
                        root.instructions().manifest().id(),
                        root.instructions().manifest().canonicalHash(),AT.plusSeconds(31),null);
                assertInstanceOf(ResumeRunAdmissionResult.Success.class,
                        store.r1aDurable.admitResumeRun(next,"runtime-b","lease-b",
                                AT.plusSeconds(31),AT.plusSeconds(61),Duration.ZERO));
                return new CodingTaskResult(task.value(),runtimeA.resumeRun().id().value(),null,
                        TaskStore.STATUS_DONE,null,0,"stale terminal",guardA);
            }
        };
        InteractiveRuntimeAdapter adapter=new InteractiveRuntimeAdapter(workspace,executor,
                ignored->{},new InteractiveRuntimeView(),state);
        try{
            InteractiveSession session=session();adapter.openSession(session);
            new SavedSessionService(state,workspace,Clock.fixed(AT.plusSeconds(1),ZoneOffset.UTC))
                    .bind(session,root.threadId().value());
            assertEquals("PREPARING",adapter.recoverTask(task.value()).code());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals("EXECUTION_AUTHORITY_LOST",adapter.lastResult().orElseThrow().code());
            assertEquals(TaskStore.STATUS_RUNNING,state.tasks.findById(task.value())
                    .orElseThrow().status());
            assertEquals("runtime-b",state.r1aDurable.readOwnership(task)
                    .orElseThrow().ownerInstanceId());
        }finally{adapter.close();}
    }

    @Test
    void adapterCannotPersistCancellationAfterAuthorityTakeover()throws Exception{
        Config config=config();StateStore state=new StateStore(workspace);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"recover",10_000,
                config,new RuntimeManifestHasher(workspace).compute(config),AT);
        state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("recover")));
        TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace)).recover(
                        task,config,new RuntimeManifestHasher(workspace).compute(config),10_000,
                        new TaskRecoveryService.LeaseRequest("runtime-a","lease-a",AT,
                                AT.plusSeconds(30),Duration.ZERO)));
        FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                Duration.ofSeconds(30),()->AT.plusSeconds(1));
        InteractiveRuntimeTaskExecutor executor=new InteractiveRuntimeTaskExecutor(){
            public CodingTaskResult execute(String id,
                    com.paicli.interactive.task.InteractiveTaskRequest request,StateStore store,
                    RuntimeEventBridge bridge){throw new UnsupportedOperationException();}
            public CodingTaskResult resume(String id,StateStore store,RuntimeEventBridge bridge)
                    throws Exception{
                bridge.bindExecutionGuard(guardA);
                DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),task,
                        root.threadId(),"RESUME",runtimeA.resumeRun().id(),
                        root.instructions().manifest().id(),
                        root.instructions().manifest().canonicalHash(),AT.plusSeconds(31),null);
                assertInstanceOf(ResumeRunAdmissionResult.Success.class,
                        store.r1aDurable.admitResumeRun(next,"runtime-b","lease-b",
                                AT.plusSeconds(31),AT.plusSeconds(61),Duration.ZERO));
                throw new CancellationToken.TaskCancellationException(false);
            }
        };
        InteractiveRuntimeAdapter adapter=new InteractiveRuntimeAdapter(workspace,executor,
                ignored->{},new InteractiveRuntimeView(),state);
        try{
            InteractiveSession session=session();adapter.openSession(session);
            new SavedSessionService(state,workspace,Clock.fixed(AT.plusSeconds(1),ZoneOffset.UTC))
                    .bind(session,root.threadId().value());
            assertEquals("PREPARING",adapter.recoverTask(task.value()).code());
            assertTrue(adapter.awaitIdle(Duration.ofSeconds(5)));
            assertEquals("EXECUTION_AUTHORITY_LOST",adapter.lastResult().orElseThrow().code());
            assertEquals(TaskStore.STATUS_RUNNING,state.tasks.findById(task.value())
                    .orElseThrow().status());
            assertEquals("runtime-b",state.r1aDurable.readOwnership(task)
                    .orElseThrow().ownerInstanceId());
        }finally{adapter.close();}
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model-a",
                "", "", "system", 3, "", 120, 50_000, 1,
                false, false, false);
    }

    private static String passCommand(){
        return System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT)
                .contains("windows")?"exit 0":"true";
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("recovery-session", workspace,
                Config.PROVIDER_MOCK, "model-a", Config.MODE_DENY,
                InteractiveSession.TerminalMode.PLAIN, AT.plusSeconds(1),
                "off", "none");
        session.start();
        return session;
    }
}
