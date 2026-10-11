package com.paicli.context;

import com.paicli.config.Config;
import com.paicli.context.ContextModels.*;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.*;
import com.paicli.state.*;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.SteeringModels.Provenance;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class R3AuthorityDrivenContextEvidenceTest {
    @TempDir Path workspace;

    @Test void realPlanAndSteeringAuthorityRevisionsDriveNewReadyContexts()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            AgentMessage goal=AgentMessage.userGoal("authority-driven goal");
            admitted.guard().appendEvent(0,goal);
            RecordingProvider recording=new RecordingProvider();
            ContextualModelProvider provider=provider(state,workspace,admitted.root().taskId().value(),
                    recording,false);

            Revision revisionOne=activatePlan(state,admitted.guard().current().snapshot(),1,0,
                    "plan revision one");
            completeDirectFinal(provider.chat(OperationKind.CHAT,"plan-revision-one",List.of(goal),
                    List.of(),admitted.guard()),admitted.guard());
            String ctxA=pointer(state,admitted,OperationKind.CHAT);
            var generationA=state.contexts.find(ctxA).orElseThrow();
            assertEquals(revisionOne.planRevisionId().value(),generationA.planRevision());

            Revision revisionTwo=activatePlan(state,admitted.guard().current().snapshot(),2,1,
                    "plan revision two");
            completeDirectFinal(provider.chat(OperationKind.CHAT,"plan-revision-two",List.of(goal),
                    List.of(),admitted.guard()),admitted.guard());
            String ctxB=pointer(state,admitted,OperationKind.CHAT);
            var generationB=state.contexts.find(ctxB).orElseThrow();
            assertNotEquals(ctxA,ctxB);assertEquals(revisionTwo.planRevisionId().value(),generationB.planRevision());
            assertEquals(revisionOne.planRevisionId().value(),state.contexts.find(ctxA).orElseThrow().planRevision());
            assertTrue(recording.visible().contains(revisionTwo.planRevisionId().value()));
            assertFalse(recording.visible().contains(revisionOne.planRevisionId().value()));

            var target=state.steerings.captureTarget(admitted.root().taskId(),
                    admitted.root().initialRun().id()).target();
            var steering=state.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:r3","r3",Instant.now());
            assertNotNull(steering.steeringId());
            var applied=new SteeringSafePointCoordinator(state,admitted.guard(),()->false)
                    .check(SteeringSafePointCoordinator.Candidate.PROVIDER_REQUEST_BEFORE);
            assertTrue(applied.applied());
            completeDirectFinal(provider.chat(OperationKind.CHAT,"steering-revision",List.of(goal),
                    List.of(),admitted.guard()),admitted.guard());
            String ctxC=pointer(state,admitted,OperationKind.CHAT);
            var generationC=state.contexts.find(ctxC).orElseThrow();
            assertNotEquals(ctxB,ctxC);assertNotNull(generationC.steeringRevision());
            assertNull(generationB.steeringRevision());
            assertTrue(recording.visible().contains("Current durable active Constraints"));
            assertTrue(recording.visible().contains("format: concise"));
        }
    }

    @Test void freshRootManifestsAndExternalDriftUseRealFrozenAuthority()throws Exception{
        Path rootA=workspace.resolve("root-a"),rootB=workspace.resolve("root-b");
        Files.createDirectories(rootA);Files.createDirectories(rootB);
        try(StateStore state=new StateStore(workspace)){
            LocalAdmitted first=admit(state,rootA,"root rule alpha");
            AgentMessage firstGoal=AgentMessage.userGoal("first root goal");
            first.guard().appendEvent(0,firstGoal);
            RecordingProvider firstRecording=new RecordingProvider();
            ContextualModelProvider firstProvider=provider(state,rootA,first.root().taskId().value(),
                    firstRecording,false);
            completeDirectFinal(firstProvider.chat(OperationKind.CHAT,"root-a",List.of(firstGoal),
                    List.of(),first.guard()),first.guard());
            String ctxA=state.contexts.pointer(first.root().taskId().value(),
                    first.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow()
                    .contextGenerationId();
            String frozenHash=state.contexts.find(ctxA).orElseThrow().manifestHash();

            Files.writeString(rootA.resolve("AGENTS.md"),"externally changed rule");
            completeDirectFinal(firstProvider.chat(OperationKind.CHAT,"root-a-after-drift",
                    List.of(firstGoal),List.of(),first.guard()),first.guard());
            String driftCtx=state.contexts.pointer(first.root().taskId().value(),
                    first.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow()
                    .contextGenerationId();
            assertEquals(frozenHash,state.contexts.find(driftCtx).orElseThrow().manifestHash(),
                    "live workspace drift cannot reinterpret committed Manifest authority");

            LocalAdmitted second=admit(state,rootB,"fresh root rule beta");
            AgentMessage secondGoal=AgentMessage.userGoal("second root goal");
            second.guard().appendEvent(0,secondGoal);
            RecordingProvider secondRecording=new RecordingProvider();
            completeDirectFinal(provider(state,rootB,second.root().taskId().value(),
                    secondRecording,false).chat(OperationKind.CHAT,"root-b",List.of(secondGoal),
                            List.of(),second.guard()),second.guard());
            String ctxB=state.contexts.pointer(second.root().taskId().value(),
                    second.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow()
                    .contextGenerationId();
            assertNotEquals(ctxA,ctxB);
            assertNotEquals(frozenHash,state.contexts.find(ctxB).orElseThrow().manifestHash());
            assertEquals(frozenHash,state.contexts.find(ctxA).orElseThrow().manifestHash());
        }
    }

    @Test void recoveryServiceCreatesNewRunAndNormalProviderPathCreatesNewContext()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            AgentMessage goal=AgentMessage.userGoal("recoverable durable goal");
            admitted.guard().appendEvent(0,goal);
            RecordingProvider recording=new RecordingProvider();
            ContextualModelProvider provider=provider(state,workspace,admitted.root().taskId().value(),
                    recording,false);
            completeDirectFinal(provider.chat(OperationKind.CHAT,"initial-run",List.of(goal),
                    List.of(),admitted.guard()),admitted.guard());
            String ctxA=pointer(state,admitted,OperationKind.CHAT);
            var oldAuthority=admitted.guard().current().snapshot();
            var oldPointer=state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow();
            state.checkpoints.save(admitted.root().taskId().value(),1,"RUNNING",List.of(goal));

            Instant recoveredAt=Instant.now().plus(Duration.ofHours(2));
            TaskRecoveryService.RecoveryResult result=new TaskRecoveryService(state,
                    new InstructionManifestRuntime(state,workspace)).recover(admitted.root().taskId(),
                    config(workspace),RuntimeManifest.disabled(),10_000,
                    new TaskRecoveryService.LeaseRequest("r3-recovery-owner","r3-recovery-lease",
                            recoveredAt,recoveredAt.plusSeconds(60),Duration.ZERO));
            TaskRecoveryService.Success recovered=assertInstanceOf(TaskRecoveryService.Success.class,result);
            assertNotEquals(admitted.root().initialRun().id(),recovered.resumeRun().id());
            assertEquals(oldAuthority.fencingToken()+1,recovered.executionAuthority().fencingToken());
            FencedExecutionGuard recoveryGuard=new FencedExecutionGuard(state,recovered.executionAuthority(),
                    Duration.ofSeconds(30),()->recoveredAt.plusSeconds(1));
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    state.contexts.safePoint(oldPointer,oldAuthority).code());
            completeDirectFinal(provider.chat(OperationKind.CHAT,"resume-run",
                    recovered.conversation(),List.of(),recoveryGuard),recoveryGuard);
            String ctxB=state.contexts.pointer(admitted.root().taskId().value(),
                    recovered.resumeRun().id().value(),OperationKind.CHAT).orElseThrow()
                    .contextGenerationId();
            assertNotEquals(ctxA,ctxB);
            assertEquals(admitted.root().initialRun().id().value(),state.contexts.find(ctxA).orElseThrow().runId());
            assertEquals(recovered.resumeRun().id().value(),state.contexts.find(ctxB).orElseThrow().runId());
            assertFalse(state.contexts.references(ctxB).stream()
                    .anyMatch(reference->reference.id().equals(ctxA)));
            var resumedPointer=state.contexts.pointer(admitted.root().taskId().value(),
                    recovered.resumeRun().id().value(),OperationKind.CHAT).orElseThrow();
            assertTrue(state.contexts.safePoint(resumedPointer,recoveryGuard.current().snapshot()).safe());
            assertEquals(2,recording.calls.get(),
                    "two wrapper calls are two Provider invocations; no transport-retry capability is implied");
        }
    }

    private Revision activatePlan(StateStore state,
            com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot authority,
            int number,long expectedPointerVersion,String marker){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition definition=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,marker,marker,
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.READ,
                List.of(exactReadRequirement()),"b".repeat(64));
        Revision draft=new Revision(plan,authority.taskId(),number,marker,"accept","","","verify",
                "0".repeat(64),"USER","r3-evidence",authority.runId(),Instant.now(),
                List.of(definition),List.of(),List.of(),Revision.CANONICAL_SCHEMA_V3);
        Revision revision=new Revision(plan,authority.taskId(),number,draft.goal(),draft.acceptanceCriteria(),
                draft.assumptions(),draft.risks(),draft.verificationStrategy(),PlanCanonicalizer.hash(draft),
                draft.sourceType(),draft.provenance(),authority.runId(),draft.createdAt(),draft.steps(),
                draft.dependencies(),draft.lineage(),Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(
                authority,Instant.now(),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(
                authority,Instant.now(),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                authority,Instant.now(),plan,1,expectedPointerVersion,"approval-"+number,
                "user","r3-evidence",null).status());
        return revision;
    }

    private static void completeDirectFinal(ModelResponse response,FencedExecutionGuard guard){
        assertNotNull(response.providerOccurrenceId());
        guard.completeOccurrence(CanonicalId.parse(response.providerOccurrenceId()),
                com.paicli.tool.ToolResult.success("PROVIDER_RESPONSE_FINAL"));
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement(){
        String json="{\"path\":\"AGENTS.md\",\"maxLines\":null,"
                +"\"includeInternal\":false}";
        var selector=new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH,json,
                PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement("read-agents",0,"read_file",
                PlanCompletionModels.EffectClass.READ,selector,1,1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private LocalAdmitted admit(StateStore state,Path root,String manifestText)throws Exception{
        Files.writeString(root.resolve("AGENTS.md"),manifestText);
        var admitted=new InstructionManifestRuntime(state,root).admitRoot(
                CanonicalId.newId(EntityFamily.TASK),"goal",10_000,config(root),
                RuntimeManifest.disabled(),Instant.now());
        Instant at=Instant.now();
        assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(admitted.taskId(),
                "owner-"+admitted.taskId().value(),admitted.initialRun().id(),
                "lease-"+admitted.taskId().value(),at,at.plus(Duration.ofHours(1))));
        var lease=state.r1aDurable.readOwnership(admitted.taskId()).orElseThrow();
        return new LocalAdmitted(admitted,new FencedExecutionGuard(state,
                TaskExecutionAuthority.initial(lease),Duration.ofHours(1),Instant::now));
    }

    private static ContextualModelProvider provider(StateStore state,Path root,String taskId,
            ModelProvider delegate,boolean child){
        return new ContextualModelProvider(delegate,state,root,taskId,"mock","model",OperationKind.CHAT,
                new CapabilityProfile("mock",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                child,"runtime");
    }
    private static String pointer(StateStore state,R3ContextTestFixtures.Admitted admitted,
            OperationKind operation) throws java.sql.SQLException {
        return state.contexts.pointer(admitted.root().taskId().value(),
                admitted.root().initialRun().id().value(),operation).orElseThrow().contextGenerationId();
    }
    private static Config config(Path root){
        return new Config(root.toString(),30,50_000,200_000,Config.MODE_DENY,10_000,
                Config.PROVIDER_MOCK,"model",null,null,"system",3,"",120,50_000,1,
                false,false,false);
    }
    private record LocalAdmitted(InstructionManifestRuntime.RootAdmission root,FencedExecutionGuard guard){}
    private static final class RecordingProvider implements ModelProvider{
        private final AtomicInteger calls=new AtomicInteger();
        private final AtomicReference<List<AgentMessage>> messages=new AtomicReference<>(List.of());
        @Override public ModelResponse chat(List<AgentMessage> actual,List<ToolDefinition> tools){
            calls.incrementAndGet();messages.set(List.copyOf(actual));return ModelResponse.finalAnswer("ok");
        }
        String visible(){return messages.get().stream().map(AgentMessage::content)
                .collect(Collectors.joining("\n"));}
    }
}
