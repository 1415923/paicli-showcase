package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopControl;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.config.Config;
import com.paicli.identity.*;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.*;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.R1aStoreResults.*;
import com.paicli.state.SteeringModels.Provenance;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class R1dSteeringRuntimeTest {
    @TempDir Path workspace;
    private static final Instant AT=Instant.parse("2026-07-19T00:00:00Z");

    @Test void agentLoopSafePointAppliesConstraintExactlyOnceBeforeProviderRequest()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state);assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(
                    seed.task,"runtime",seed.run,"lease",AT,AT.plusSeconds(60)));
            var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
            var guard=new FencedExecutionGuard(state,TaskExecutionAuthority.initial(lease),
                    Duration.ofSeconds(30),()->AT.plusSeconds(2));
            var admitted=state.steerings.admit(state.steerings.captureTarget(seed.task,seed.run).target(),
                    new SteeringClassifier().classify("format: concise"),Provenance.INTERACTIVE_EXPLICIT,
                    "interactive:s1","s1",AT.plusSeconds(1));
            AtomicReference<List<AgentMessage>> providerMessages=new AtomicReference<>();
            var provider=(com.paicli.provider.ModelProvider)(messages,tools)->{
                providerMessages.set(List.copyOf(messages));return ModelResponse.finalAnswer("done");};
            AgentLoop loop=new AgentLoop(provider,new ToolRegistry(),2,null,null,state,
                    seed.task.value(),"mock","model").bindExecutionGuard(guard);
            var coordinator=new SteeringSafePointCoordinator(state,guard,loop::hasPendingApproval);
            loop.bindSteeringCoordinator(coordinator);
            assertTrue(loop.runSegment(new AgentSegmentRequest("goal",
                    List.of(AgentMessage.userGoal("goal")),null,0,1)).completed());
            assertTrue(providerMessages.get().stream().map(AgentMessage::content)
                    .anyMatch(value->value.contains("Current durable active Constraints")
                            &&value.contains("format: concise")));
            assertEquals(SteeringModels.State.APPLIED,state.steerings.readQueue(
                    admitted.steeringId()).orElseThrow().state());
            assertEquals(SteeringStore.Status.NOT_FOUND,coordinator.check(
                    SteeringSafePointCoordinator.Candidate.NEXT_STEP_ATTEMPT_BEFORE).status());
        }
    }

    @Test void pendingApprovalMakesCandidateBoundaryUnsafeWithoutDroppingQueue()throws Exception{
        try(StateStore state=new StateStore(workspace.resolve("pending"))){
            Seed seed=seed(state);assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(
                    seed.task,"runtime",seed.run,"lease",AT,AT.plusSeconds(60)));
            var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
            var guard=new FencedExecutionGuard(state,TaskExecutionAuthority.initial(lease),
                    Duration.ofSeconds(30),()->AT.plusSeconds(2));
            var admitted=state.steerings.admit(state.steerings.captureTarget(seed.task,seed.run).target(),
                    new SteeringClassifier().classify("format: concise"),Provenance.INTERACTIVE_EXPLICIT,
                    "interactive:s1","s1",AT.plusSeconds(1));
            var coordinator=new SteeringSafePointCoordinator(state,guard,()->true);
            var blocked=coordinator.check(SteeringSafePointCoordinator.Candidate.PROVIDER_REQUEST_BEFORE);
            assertEquals(SteeringStore.Status.UNSAFE_POINT,blocked.status());assertTrue(blocked.pauseExecution());
            assertEquals(SteeringModels.State.QUEUED,state.steerings.readQueue(
                    admitted.steeringId()).orElseThrow().state());
        }
    }

    @Test void materialSteeringAtRuntimeBoundaryCreatesDraftAndPausesWithoutActivatingIt()throws Exception{
        try(StateStore state=new StateStore(workspace.resolve("material"))){
            Seed seed=seed(state,StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
            assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(
                    seed.task,"runtime",seed.run,"lease",AT,AT.plusSeconds(60)));
            var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
            var authority=TaskExecutionAuthority.initial(lease);
            CanonicalId active=approvePlan(state,seed,authority.snapshot());
            var guard=new FencedExecutionGuard(state,authority,Duration.ofSeconds(30),()->AT.plusSeconds(3));
            state.steerings.admit(state.steerings.captureTarget(seed.task,seed.run).target(),
                    new SteeringClassifier().classify("change plan scope"),Provenance.INTERACTIVE_EXPLICIT,
                    "interactive:s1","s1",AT.plusSeconds(2));
            var applied=new SteeringSafePointCoordinator(state,guard,()->false,
                    request->materialDraft(request,seed.run),iteration->null).check(
                    SteeringSafePointCoordinator.Candidate.NEXT_STEP_ATTEMPT_BEFORE);
            assertEquals(SteeringStore.Status.SUCCESS,applied.status());assertTrue(applied.pauseExecution());
            assertEquals(active,state.planAuthorities.readActive(seed.task).plan().revision().planRevisionId());
            CanonicalId draft=state.steerings.latestAppliedPlanDraft(seed.task,seed.run).orElseThrow();
            var source=state.planAuthorities.readRevisionDefinition(seed.task,active).revision();
            var created=state.planAuthorities.readRevisionDefinition(seed.task,draft).revision();
            assertNotEquals(source.steps().getFirst().definition(),
                    created.steps().getFirst().definition());
        }
    }

    @Test void planServiceMaterialReplanGeneratesNewExecutableDefinitions()throws Exception{
        try(StateStore state=new StateStore(workspace.resolve("plan-service-replan"))){
            Seed seed=seed(state,StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
            assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(
                    seed.task,"runtime",seed.run,"lease",AT,AT.plusSeconds(60)));
            var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
            var authority=TaskExecutionAuthority.initial(lease);
            CanonicalId active=approvePlan(state,seed,authority.snapshot());
            var guard=new FencedExecutionGuard(state,authority,Duration.ofSeconds(30),
                    ()->AT.plusSeconds(2));
            String json="""
                    {"acceptanceCriteria":"updated","assumptions":"","risks":"",
                     "verificationStrategy":"verify","steps":[{
                       "title":"implement changed scope",
                       "definition":"execute the changed material intent",
                       "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
                       "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"WRITE",
                       "toolRequirements":[{"requirementId":"write-changed-scope",
                         "requirementOrder":0,"toolName":"write_file","effectClass":"WRITE",
                         "authoritySelector":{"kind":"EXACT_PATH","path":"A.java","overwrite":true},
                         "minSuccesses":1,"maxOccurrences":1,
                         "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
                    """;
            PlanService service=new PlanService((messages,tools)->
                    ModelResponse.finalAnswer(json),state.plans);
            var source=state.planAuthorities.readRevisionDefinition(seed.task,active).revision();
            CanonicalId steering=CanonicalId.newId(EntityFamily.STEERING);
            var draft=service.generateSteeredStructured(source,steering,
                    "change plan scope","",guard,2,AT.plusSeconds(2));
            assertEquals("R1D_STEERING:"+steering.value(),draft.provenance());
            assertNotEquals(source.steps().getFirst().stepRevisionId(),
                    draft.steps().getFirst().stepRevisionId());
            assertNotEquals(source.steps().getFirst().logicalStepId(),
                    draft.steps().getFirst().logicalStepId());
            assertNotEquals(source.steps().getFirst().definition(),
                    draft.steps().getFirst().definition());
            assertTrue(draft.lineage().stream().anyMatch(lineage->
                    lineage.relationship()==StructuredPlanModels.LineageRelationship.ADDED));
            assertTrue(draft.lineage().stream().anyMatch(lineage->
                    lineage.relationship()==StructuredPlanModels.LineageRelationship.REMOVED));
        }
    }

    @Test void approvalThenSteeringRaceRevalidatesAtToolLaunchAndInvokesNothing()
            throws Exception{
        try(StateStore state=new StateStore(workspace.resolve("tool-race"))){
            Seed seed=seed(state,StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
            assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(
                    seed.task,"runtime",seed.run,"lease",AT,AT.plusSeconds(60)));
            var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
            var authority=TaskExecutionAuthority.initial(lease);
            CanonicalId active=approvePlan(state,seed,authority.snapshot());
            var started=state.planExecutions.startNextReadyAttempt(authority.snapshot(),
                    AT.plusSeconds(2),4,"R1D_TOOL_LAUNCH_RACE_TEST");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
            var guard=new FencedExecutionGuard(state,authority,Duration.ofSeconds(30),
                    ()->AT.plusSeconds(3));
            AtomicInteger toolInvocations=new AtomicInteger();
            Tool authorityProjectionTool=new com.paicli.tool.WriteFileTool(
                    new com.paicli.safety.SafetyPolicy(workspace));
            ToolRegistry tools=new ToolRegistry();
            tools.register(new Tool(){
                public String name(){return "write_file";}
                public String description(){return "test write";}
                public ToolDefinition toDefinition(){return new ToolDefinition(name(),description(),
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());}
                public Optional<com.paicli.tool.ToolAuthorityProjection> authorityProjection(
                        ToolCall call){return authorityProjectionTool.authorityProjection(call);}
                public ToolResult execute(ToolCall call){
                    toolInvocations.incrementAndGet();return ToolResult.success("written");
                }
            });
            AtomicInteger providerCalls=new AtomicInteger();
            var provider=(com.paicli.provider.ModelProvider)(messages,definitions)->
                    providerCalls.getAndIncrement()==0
                            ?ModelResponse.toolCallResponse("write",
                                    new ToolCall("write_file",Map.of("path","A.java",
                                            "content","x","overwrite",true)))
                            :ModelResponse.finalAnswer("must not continue old response");
            AtomicReference<CanonicalId> steeringId=new AtomicReference<>();
            AgentLoopControl control=new AgentLoopControl(){
                public ApprovalDecision resolveApproval(String taskId,int iteration,
                        ToolCall call,ApprovalDecision policyDecision){
                    var admitted=state.steerings.admit(
                            state.steerings.captureTarget(seed.task,seed.run).target(),
                            new SteeringClassifier().classify("change plan scope"),
                            Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",
                            AT.plusSeconds(3));
                    steeringId.set(admitted.steeringId());
                    return ApprovalDecision.allow(policyDecision.toolName(),
                            policyDecision.riskLevel(),policyDecision.taskId(),"user approved");
                }
            };
            ApprovalPolicy approval=new ApprovalPolicy(new Config(workspace.toString(),30,
                    50_000,200_000,Config.MODE_ON_REQUEST,10_000),seed.task.value());
            AgentLoop loop=new AgentLoop(provider,tools,2,approval,null,state,
                    seed.task.value(),"mock","model",AgentLoopObserver.NOOP,control)
                    .bindExecutionGuard(guard);
            var coordinator=new SteeringSafePointCoordinator(state,guard,loop::hasPendingApproval,
                    request->materialDraft(request,seed.run),
                    endIteration->new SteeringStore.AttemptPause(started.value().attempt(),
                            started.value().step().transitionVersion(),endIteration));
            loop.bindSteeringCoordinator(coordinator);
            var result=loop.runSegment(new AgentSegmentRequest("goal",List.of(),null,4,2,
                    new AgentSegmentRequest.PlanExecutionContext(seed.task,active,
                            started.value().attempt().stepRevisionId(),seed.run,
                            started.value().attempt().attemptNumber())));
            assertEquals(com.paicli.agent.AgentSegmentResult.Outcome.PAUSED,result.outcome());
            assertEquals(0,toolInvocations.get());
            assertEquals(1,providerCalls.get());
            assertTrue(state.r1aDurable.findRecoveryBlockingOccurrences(
                    seed.task,seed.run,"TOOL").isEmpty());
            assertEquals(SteeringModels.State.APPLIED,state.steerings
                    .readQueue(steeringId.get()).orElseThrow().state());
            assertEquals(PlanExecutionModels.AttemptState.BLOCKED,state.planExecutions
                    .readRecovery(seed.task,active).attempts().getFirst().state());
            assertTrue(state.steerings.latestAppliedPlanDraft(seed.task,seed.run).isPresent());
        }
    }

    private static StructuredPlanModels.Revision materialDraft(
            SteeringStore.MaterialPlanRequest request,CanonicalId run){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        CanonicalId step=CanonicalId.newId(EntityFamily.PLAN_STEP);
        String provenance="R1D_STEERING:"+request.definition().steeringId().value();
        var definition=new StructuredPlanModels.StepDefinition(step,
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"updated execution",
                "execute the Steering-updated goal",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.WRITE,
                List.of(exactWriteRequirement("write-steering-output","steering-output.txt",false)),
                "c".repeat(64));
        List<StructuredPlanModels.Lineage> lineage=new ArrayList<>();
        lineage.add(new StructuredPlanModels.Lineage("lineage-added-"+UUID.randomUUID(),
                null,null,plan,step,StructuredPlanModels.LineageRelationship.ADDED,
                provenance,AT.plusSeconds(3)));
        for(var old:request.sourceRevision().steps())lineage.add(
                new StructuredPlanModels.Lineage("lineage-removed-"+UUID.randomUUID(),
                        request.sourceRevision().planRevisionId(),old.stepRevisionId(),plan,null,
                        StructuredPlanModels.LineageRelationship.REMOVED,provenance,
                        AT.plusSeconds(3)));
        var zero=new StructuredPlanModels.Revision(plan,request.sourceRevision().taskId(),
                request.revisionNumber(),request.sourceRevision().goal()+"\nMaterial Steering: "
                +request.definition().content(),"updated acceptance","","","verify",
                "0".repeat(64),"STEERING",provenance,run,AT.plusSeconds(3),
                List.of(definition),List.of(),lineage,
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        return new StructuredPlanModels.Revision(plan,zero.taskId(),zero.revisionNumber(),
                zero.goal(),zero.acceptanceCriteria(),zero.assumptions(),zero.risks(),
                zero.verificationStrategy(),PlanCanonicalizer.hash(zero),zero.sourceType(),
                zero.provenance(),zero.createdByRunId(),zero.createdAt(),zero.steps(),
                zero.dependencies(),zero.lineage(),StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
    }

    private static CanonicalId approvePlan(StateStore state,Seed seed,ExecutionAuthoritySnapshot authority){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN),step=CanonicalId.newId(EntityFamily.PLAN_STEP);
        var definition=new StructuredPlanModels.StepDefinition(step,CanonicalId.newId(EntityFamily.PLAN_STEP),0,
                "execute","execute safely",PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),PlanCompletionModels.EffectClass.WRITE,
                List.of(exactWriteRequirement("write-a","A.java",true)),"b".repeat(64));
        var zero=new StructuredPlanModels.Revision(plan,seed.task,1,"goal","accept","","","verify",
                "0".repeat(64),"TEST","R1D_RUNTIME_TEST",seed.run,AT.plusSeconds(1),List.of(definition),
                List.of(),List.of(),StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        var revision=new StructuredPlanModels.Revision(plan,seed.task,1,zero.goal(),zero.acceptanceCriteria(),
                zero.assumptions(),zero.risks(),zero.verificationStrategy(),PlanCanonicalizer.hash(zero),zero.sourceType(),
                zero.provenance(),seed.run,zero.createdAt(),zero.steps(),zero.dependencies(),zero.lineage(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(authority,AT.plusSeconds(1),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(authority,AT.plusSeconds(1),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(authority,AT.plusSeconds(1),
                plan,1,0,"approval-r1d-runtime","tester","v1","approved").status());
        return plan;
    }

    private static StructuredPlanModels.ToolRequirement exactWriteRequirement(
            String id,String path,boolean overwrite){
        String json="{\"path\":\""+path+"\",\"overwrite\":"+overwrite+"}";
        var selector=new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH,json,
                PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement(id,0,"write_file",
                PlanCompletionModels.EffectClass.WRITE,selector,1,1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private static Seed seed(StateStore state)throws Exception{
        return seed(state,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
    }
    private static Seed seed(StateStore state,StructuredPlanModels.PlanApplicability applicability)throws Exception{
        CanonicalId thread=CanonicalId.newId(EntityFamily.THREAD),task=CanonicalId.newId(EntityFamily.TASK),
                run=CanonicalId.newId(EntityFamily.RUN),generation=CanonicalId.newId(EntityFamily.GENERATION);
        String manifest="manifest-"+UUID.randomUUID();
        ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inlineUtf8(
                "artifact-"+UUID.randomUUID(),"frozen","v1","TASK_AUTHORITY",AT);
        var entry=new InstructionManifestCandidate.Entry("entry-"+UUID.randomUUID(),"PROJECT_FILE",
                "AGENTS.md","WORKSPACE",InstructionManifestCandidate.Status.APPLIED,0,
                artifact.contentHash(),artifact.id(),"UTF8",null,null);
        var candidate=new InstructionManifestCandidate(1,"root-paicli-agents-v1",List.of(entry));
        var request=new RootAdmissionTransaction.Request(new WorkThread(thread,"workspace","goal","OPEN",AT,AT),
                new DurableTask(task,thread,null,"goal","CREATED",null,null,AT,null),manifest,candidate,List.of(artifact),
                new DurableRun(run,task,thread,"INITIAL",null,manifest,candidate.canonicalHash(),AT,null),
                new TaskContextGeneration(generation,task,null,run,manifest,candidate.canonicalHash(),"COMMITTED","{}",AT,AT),
                new TaskContextPointer(task,generation,run,0),
                new RootAdmissionTransaction.RuntimeTask(task.value(),"goal","mock","model","deny",3,"RUNNING"),
                new RootAdmissionTransaction.RuntimeRun(run.value(),task.value(),"INITIAL","PLANNING","",1,0,
                        "skills-disabled","hooks-disabled"),applicability,AT);
        assertInstanceOf(RootAdmissionTransaction.Success.class,state.r1aDurable.admitRootTaskAtomically(request));
        return new Seed(task,run);
    }

    private record Seed(CanonicalId task,CanonicalId run) { }
}
