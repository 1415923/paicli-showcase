package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.state.TaskContinuityStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.runtime.TaskContinuityModels.*;
import static org.junit.jupiter.api.Assertions.*;

class AutonomousTaskContinuityAcceptanceTest {
    private static final String VALID_PLAN="""
            {"acceptanceCriteria":["done"],"assumptions":[],"risks":[],
             "verificationStrategy":"runtime result","steps":[
             {"title":"Work","definition":"Complete the task","dependsOn":[]}]}
            """;
    private static final String INVALID_PLAN="""
            {"acceptanceCriteria":["done"],"steps":[],"unexpected":true}
            """;

    @TempDir Path workspace;

    @Test void featureIsDisabledWithoutExplicitPolicy() throws Exception {
        assertFalse(TaskContinuityPolicy.disabled().enabled());
        assertThrows(IllegalArgumentException.class,()->new TaskContinuityPolicy(
                true,0,1,1,1,1,1,1));
        assertEquals("plan:1",PlanService.planOperationIdentity(1,1,null));
        assertEquals("plan:1:repair",PlanService.planOperationIdentity(1,1,"repair"));
        assertEquals("plan:1:attempt:2",PlanService.planOperationIdentity(1,2,null));
        assertEquals("plan:1:attempt:2:repair",
                PlanService.planOperationIdentity(1,2,"repair"));
        AtomicInteger planCalls=new AtomicInteger();
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet();return ModelResponse.finalAnswer(VALID_PLAN);
        };
        ModelProvider execution=(messages,tools)->{
            executionCalls.incrementAndGet();return ModelResponse.finalAnswer("done");
        };
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=baseRunner(state,planning,execution,new ToolRegistry(),
                    config(Config.MODE_ON_REQUEST,3)).run(
                    CanonicalId.newId(EntityFamily.TASK).value(),"work");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus());
            assertEquals(0,state.taskContinuity.count(result.taskId()));
        }
    }

    @Test void recoverablePlanFailureUsesUniqueBoundedAttemptAndCompletes() throws Exception {
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->switch(planCalls.incrementAndGet()){
            case 1,2->ModelResponse.finalAnswer(INVALID_PLAN);
            case 3->ModelResponse.finalAnswer(VALID_PLAN);
            default->throw new AssertionError("unexpected Plan call");
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->{
            executionCalls.incrementAndGet();
            return ModelResponse.finalAnswer("done");
        };
        try(StateStore state=new StateStore(workspace)){
            CodingTaskRunner runner=runner(state,planning,execution,new ToolRegistry(),
                    config(Config.MODE_ON_REQUEST,3),budget(planCalls,executionCalls,state));
            CodingTaskResult result=runner.run(CanonicalId.newId(EntityFamily.TASK).value(),"work");

            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(3,planCalls.get());
            assertEquals(1,executionCalls.get());
            List<TaskContinuityStore.Segment> segments=state.taskContinuity
                    .findByTask(result.taskId());
            assertEquals(2,segments.size());
            assertEquals("STRUCTURED_PLAN_REPAIR_FAILED",segments.getFirst().terminalReason());
            assertEquals(1,segments.getFirst().planningAttemptOrdinal());
            assertEquals(2,segments.getLast().planningAttemptOrdinal());
            assertEquals(TaskContinuityStore.COMPLETED,segments.getLast().state());
        }
    }

    @Test void repeatedEffectfulBatchStaysInSameSegmentAndNeverPartiallyExecutes()
            throws Exception {
        Files.createDirectories(workspace.resolve("work"));
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet(); return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ToolCall rejectedA=new ToolCall("write_file",Map.of(
                "path","work/rejected-a.txt","content","bad"),"batch-a");
        ToolCall rejectedB=new ToolCall("write_file",Map.of(
                "path","work/rejected-b.txt","content","bad"),"batch-b");
        ModelProvider execution=(messages,tools)->switch(executionCalls.incrementAndGet()){
            case 1,2->ModelResponse.toolCallBatchResponse("batch",List.of(rejectedA,rejectedB),
                    null,false);
            case 3->ModelResponse.toolCallResponse("serialized",new ToolCall("write_file",Map.of(
                    "path","work/accepted.txt","content","ok"),"single-write"));
            case 4->ModelResponse.finalAnswer("done");
            default->throw new AssertionError("unexpected execution call");
        };
        ToolRegistry tools=new ToolRegistry();
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            tools.register(new WriteFileTool(new SafetyPolicy(workspace),
                    new FileSnapshotService(workspace,state.snapshots),task.value()));
            CodingTaskRunner runner=runner(state,planning,execution,tools,
                    config(Config.MODE_WORKSPACE_WRITE,4),budget(planCalls,executionCalls,state));
            CodingTaskResult result=runner.run(task.value(),"write one safe file");

            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertFalse(Files.exists(workspace.resolve("work/rejected-a.txt")));
            assertFalse(Files.exists(workspace.resolve("work/rejected-b.txt")));
            assertEquals("ok",Files.readString(workspace.resolve("work/accepted.txt")));
            assertEquals(1,state.toolCalls.findByTask(result.taskId()).stream()
                    .filter(call->call.success()).count());
            assertEquals(1,count("side_effect_occurrences"));
            assertEquals(4,executionCalls.get());
            assertEquals(1,state.taskContinuity.findByTask(result.taskId()).size(),
                    "a second rejected batch must not create an autonomous segment");
            assertEquals(result.runId(),state.taskContinuity.findByTask(result.taskId())
                    .getFirst().runId());
            assertEquals(result.runId(),state.taskContinuity.findByTask(result.taskId())
                    .getLast().runId());
        }
    }

    @Test void iterationExhaustionPreservesWorkspaceAndDoesNotReplayCompletedEffect()
            throws Exception {
        Files.createDirectories(workspace.resolve("work"));
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet(); return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->switch(executionCalls.incrementAndGet()){
            case 1->ModelResponse.toolCallResponse("write",new ToolCall("write_file",Map.of(
                    "path","work/once.txt","content","once"),"once-write"));
            case 2->ModelResponse.finalAnswer("done");
            default->throw new AssertionError("unexpected execution call");
        };
        ToolRegistry tools=new ToolRegistry();
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            tools.register(new WriteFileTool(new SafetyPolicy(workspace),
                    new FileSnapshotService(workspace,state.snapshots),task.value()));
            CodingTaskRunner runner=runner(state,planning,execution,tools,
                    config(Config.MODE_WORKSPACE_WRITE,1),budget(planCalls,executionCalls,state));
            CodingTaskResult result=runner.run(task.value(),"write once");

            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals("once",Files.readString(workspace.resolve("work/once.txt")));
            assertEquals(1,state.toolCalls.findByTask(result.taskId()).stream()
                    .filter(call->call.success()).count());
            assertEquals(2,state.taskContinuity.findByTask(result.taskId()).size());
            assertEquals("ITERATION_EXHAUSTED",state.taskContinuity
                    .findByTask(result.taskId()).getFirst().terminalReason());
        }
    }

    @Test void taskTotalToolBudgetIsEnforcedBeforeTheNextToolExecution()throws Exception{
        Files.createDirectories(workspace.resolve("a"));
        Files.createDirectories(workspace.resolve("b"));
        Files.createDirectories(workspace.resolve("c"));
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet();return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->{
            executionCalls.incrementAndGet();
            return ModelResponse.toolCallBatchResponse("inspect",List.of(
                    new ToolCall("list_files",Map.of("path","a"),"inspect-a"),
                    new ToolCall("list_files",Map.of("path","b"),"inspect-b"),
                    new ToolCall("list_files",Map.of("path","c"),"inspect-c")),null,false);
        };
        ToolRegistry tools=new ToolRegistry();
        tools.register(new ListFilesTool(new SafetyPolicy(workspace)));
        try(StateStore state=new StateStore(workspace)){
            TaskContinuityPolicy policy=new TaskContinuityPolicy(
                    true,5,2,20,2,100_000,100_000,100_000);
            CodingTaskRunner runner=baseRunner(state,planning,execution,tools,
                    config(Config.MODE_ON_REQUEST,3)).withTaskContinuityPolicy(
                            policy,budget(planCalls,executionCalls,state));
            CodingTaskResult result=runner.run(
                    CanonicalId.newId(EntityFamily.TASK).value(),"inspect three directories");

            int requestedToolCalls=3;
            int admittedToolCalls=state.toolCalls.findByTask(result.taskId()).size();
            long executedToolCalls;
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertTrue(result.summary().contains("MAX_TOOL_CALLS_EXHAUSTED"),result.summary());
            assertEquals(3,requestedToolCalls);
            assertEquals(2,admittedToolCalls);
            assertEquals("MAX_TOOL_CALLS_EXHAUSTED",state.taskContinuity
                    .findByTask(result.taskId()).getLast().terminalReason());
            try(var database=new com.paicli.state.DatabaseManager(
                    workspace.resolve(".paicli/state"));
                    var statement=database.getConnection().prepareStatement(
                            "SELECT count(*) FROM side_effect_occurrences "
                                    +"WHERE task_id=? AND external_outcome='KNOWN_SUCCESS'")){
                statement.setString(1,result.taskId());
                try(var row=statement.executeQuery()){
                    assertTrue(row.next());executedToolCalls=row.getLong(1);
                }
            }
            assertEquals(2,executedToolCalls);
            assertTrue(executedToolCalls<=policy.maxTotalToolCalls());
        }
    }

    @Test void authoritativeInputTokenCrossingRejectsTheNextProviderCall()throws Exception{
        Files.createDirectories(workspace.resolve("src"));
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet();return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->{
            executionCalls.incrementAndGet();
            return ModelResponse.toolCallResponse("inspect",new ToolCall(
                    "list_files",Map.of("path","src"),"inspect-src"));
        };
        ToolRegistry tools=new ToolRegistry();
        tools.register(new ListFilesTool(new SafetyPolicy(workspace)));
        try(StateStore state=new StateStore(workspace)){
            TaskContinuityPolicy policy=new TaskContinuityPolicy(
                    true,5,2,20,20,100,10_000,100_000);
            BudgetEvidenceSource evidence=(task,run)->{
                try{
                    int toolCount=state.toolCalls.findByTask(task).size();
                    long input=executionCalls.get()==0?10:150;
                    return new BudgetEvidence(true,planCalls.get()+executionCalls.get(),
                            toolCount,input,10,100,List.of());
                }catch(Exception error){
                    return BudgetEvidence.unavailable("TEST_EVIDENCE_READ_FAILED");
                }
            };
            CodingTaskResult result=baseRunner(state,planning,execution,tools,
                    config(Config.MODE_ON_REQUEST,3)).withTaskContinuityPolicy(policy,evidence)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(),"inspect once");

            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertTrue(result.summary().contains("MAX_INPUT_TOKENS_EXHAUSTED"),result.summary());
            assertEquals(1,planCalls.get());
            assertEquals(1,executionCalls.get(),"the post-limit Provider call must not run");
            assertEquals(1,state.toolCalls.findByTask(result.taskId()).size(),
                    "the Tool batch returned by the already-admitted crossing call remains subject "
                            +"to ordinary Tool authority and may complete");
            assertEquals("MAX_INPUT_TOKENS_EXHAUSTED",state.taskContinuity
                    .findByTask(result.taskId()).getLast().terminalReason());
        }
    }

    @Test void exhaustedInputBudgetRejectsPlanningBeforeProviderExecution()throws Exception{
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet();return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->{
            executionCalls.incrementAndGet();return ModelResponse.finalAnswer("done");
        };
        try(StateStore state=new StateStore(workspace)){
            TaskContinuityPolicy policy=new TaskContinuityPolicy(
                    true,5,2,20,20,100,10_000,100_000);
            BudgetEvidenceSource exhausted=(task,run)->new BudgetEvidence(
                    true,1,0,100,10,100,List.of());
            CodingTaskResult result=baseRunner(state,planning,execution,new ToolRegistry(),
                    config(Config.MODE_ON_REQUEST,3)).withTaskContinuityPolicy(policy,exhausted)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(),"do not call Provider");

            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertTrue(result.summary().contains("MAX_INPUT_TOKENS_EXHAUSTED"),result.summary());
            assertEquals(0,planCalls.get());
            assertEquals(0,executionCalls.get());
            assertEquals("MAX_INPUT_TOKENS_EXHAUSTED",state.taskContinuity
                    .findByTask(result.taskId()).getLast().terminalReason());
        }
    }

    @Test void approvalUnknownEffectAndBudgetAreFailClosed() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state,config(Config.MODE_ON_REQUEST,2));
            AtomicInteger turns=new AtomicInteger(1);
            BudgetEvidenceSource complete=(task,run)->new BudgetEvidence(true,turns.get(),0,
                    10,10,10,List.of());
            TaskContinuityCoordinator coordinator=new TaskContinuityCoordinator(state,
                    new TaskContinuityPolicy(true,1,2,3,3,100,100,1000),complete);
            int segment=coordinator.beginInitialSegment(seed.task().value(),seed.run().value(),
                    1,0,seed.guard());
            seed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));

            Decision waiting=coordinator.decide(new TaskContinuityCoordinator.Request(
                    seed.task().value(),seed.run().value(),segment,1,0,
                    SegmentTerminalReason.TOOL_PREFLIGHT_APPROVAL_REQUIRED,seed.guard()));
            assertEquals(ContinuationDecision.WAIT_FOR_USER,waiting.decision());
            assertEquals(TaskStore.STATUS_RUNNING,state.tasks.findById(seed.task().value())
                    .orElseThrow().status());
            int resumed=coordinator.resumeAfterUserResolution(seed.task().value(),
                    seed.run().value(),SegmentTerminalReason.TOOL_PREFLIGHT_APPROVAL_REQUIRED,
                    1,0,seed.guard());
            assertEquals(2,resumed);
            assertTrue(coordinator.complete(seed.task().value(),resumed,0,seed.guard()));
            assertEquals(TaskContinuityStore.COMPLETED,state.taskContinuity
                    .findByTask(seed.task().value()).getLast().state());

            Seed unknownSeed=seed(state,config(Config.MODE_ON_REQUEST,2));
            int unknownSegment=coordinatorFor(state,complete).beginInitialSegment(
                    unknownSeed.task().value(),unknownSeed.run().value(),1,0,unknownSeed.guard());
            unknownSeed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            Decision unknown=coordinatorFor(state,complete).decide(
                    new TaskContinuityCoordinator.Request(unknownSeed.task().value(),
                            unknownSeed.run().value(),unknownSegment,1,0,
                            SegmentTerminalReason.UNKNOWN_EFFECT,unknownSeed.guard()));
            assertEquals(ContinuationDecision.TERMINAL_FAILURE,unknown.decision());
            assertEquals("NO_BLIND_REPLAY",unknown.code());

            Seed budgetSeed=seed(state,config(Config.MODE_ON_REQUEST,2));
            TaskContinuityCoordinator bounded=coordinatorFor(state,complete);
            int first=bounded.beginInitialSegment(budgetSeed.task().value(),
                    budgetSeed.run().value(),1,0,budgetSeed.guard());
            budgetSeed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            Decision admitted=bounded.decide(new TaskContinuityCoordinator.Request(
                    budgetSeed.task().value(),budgetSeed.run().value(),first,1,0,
                    SegmentTerminalReason.ITERATION_EXHAUSTED,budgetSeed.guard()));
            assertTrue(admitted.startsNextSegment());
            Decision exhausted=bounded.decide(new TaskContinuityCoordinator.Request(
                    budgetSeed.task().value(),budgetSeed.run().value(),
                    admitted.admittedSegmentOrdinal(),1,0,
                    SegmentTerminalReason.ITERATION_EXHAUSTED,budgetSeed.guard()));
            assertEquals(ContinuationDecision.TERMINAL_FAILURE,exhausted.decision());
            assertTrue(exhausted.eligibilityFailures().contains(
                    "MAX_AUTONOMOUS_SEGMENTS_EXHAUSTED"));
            assertEquals(TaskContinuityStore.TERMINAL_FAILED,state.taskContinuity
                    .findByTask(budgetSeed.task().value()).getLast().state());
        }
    }

    @Test void approvalBlockerKeepsTaskRunningAndPersistsWaitingProjection() throws Exception{
        AtomicInteger planCalls=new AtomicInteger();
        ModelProvider planning=(messages,tools)->{
            planCalls.incrementAndGet();return ModelResponse.finalAnswer(VALID_PLAN);
        };
        AtomicInteger executionCalls=new AtomicInteger();
        ModelProvider execution=(messages,tools)->switch(executionCalls.incrementAndGet()){
            case 1->ModelResponse.toolCallResponse("write",new ToolCall("write_file",Map.of(
                    "path","blocked.txt","content","must-not-write"),"blocked-write"));
            case 2->ModelResponse.finalAnswer("blocked");
            default->throw new AssertionError("unexpected execution call");
        };
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            ToolRegistry tools=new ToolRegistry();
            tools.register(new WriteFileTool(new SafetyPolicy(workspace),
                    new FileSnapshotService(workspace,state.snapshots),task.value()));
            CodingTaskResult result=runner(state,planning,execution,tools,
                    config(Config.MODE_DENY,3),budget(planCalls,executionCalls,state))
                    .run(task.value(),"request a write");
            assertEquals(TaskStore.STATUS_RUNNING,result.finalStatus());
            assertTrue(result.summary().startsWith("WAITING_FOR_USER"));
            assertFalse(Files.exists(workspace.resolve("blocked.txt")));
            assertEquals(TaskContinuityStore.WAITING_FOR_USER,
                    state.taskContinuity.findByTask(task.value()).getLast().state());
        }
    }

    @Test void missingBudgetEvidenceAndBrokenSafetyFactsRejectRollover() throws Exception {
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state,config(Config.MODE_ON_REQUEST,2));
            TaskContinuityCoordinator coordinator=new TaskContinuityCoordinator(state,
                    TaskContinuityPolicy.acceptanceTestPolicy(),BudgetEvidenceSource.UNAVAILABLE);
            int segment=coordinator.beginInitialSegment(seed.task().value(),seed.run().value(),
                    1,0,seed.guard());
            seed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            Decision decision=coordinator.decide(new TaskContinuityCoordinator.Request(
                    seed.task().value(),seed.run().value(),segment,1,0,
                    SegmentTerminalReason.ITERATION_EXHAUSTED,seed.guard()));
            assertEquals(ContinuationDecision.TERMINAL_FAILURE,decision.decision());
            assertTrue(decision.eligibilityFailures().contains("TASK_BUDGET_EVIDENCE_INCOMPLETE"));
            assertEquals(1,state.taskContinuity.count(seed.task().value()));
            assertEquals(TaskContinuityStore.TERMINAL_FAILED,state.taskContinuity
                    .findByTask(seed.task().value()).getLast().state());
        }
    }

    @Test void unresolvedOccurrenceTerminalTaskAndStaleLeaseEachBlockRollover()
            throws Exception{
        BudgetEvidenceSource complete=(task,run)->new BudgetEvidence(true,1,0,1,1,1,List.of());
        try(StateStore state=new StateStore(workspace)){
            Seed occurrenceSeed=seed(state,config(Config.MODE_ON_REQUEST,2));
            TaskContinuityCoordinator occurrenceCoordinator=coordinatorFor(state,complete);
            int occurrenceSegment=occurrenceCoordinator.beginInitialSegment(
                    occurrenceSeed.task().value(),occurrenceSeed.run().value(),1,0,
                    occurrenceSeed.guard());
            occurrenceSeed.guard().saveCheckpoint(0,"RUNNING",
                    List.of(AgentMessage.userGoal("goal")));
            occurrenceSeed.guard().admitOperationOccurrence("0","continuity-unknown-"
                    +java.util.UUID.randomUUID(),"TOOL","write_file","a".repeat(64));
            Decision occurrenceDecision=occurrenceCoordinator.decide(
                    new TaskContinuityCoordinator.Request(occurrenceSeed.task().value(),
                            occurrenceSeed.run().value(),occurrenceSegment,1,0,
                            SegmentTerminalReason.ITERATION_EXHAUSTED,occurrenceSeed.guard()));
            assertTrue(occurrenceDecision.eligibilityFailures().contains(
                    "STARTED_OR_UNKNOWN_EFFECT_OCCURRENCE"));

            Seed terminalSeed=seed(state,config(Config.MODE_ON_REQUEST,2));
            TaskContinuityCoordinator terminalCoordinator=coordinatorFor(state,complete);
            int terminalSegment=terminalCoordinator.beginInitialSegment(terminalSeed.task().value(),
                    terminalSeed.run().value(),1,0,terminalSeed.guard());
            terminalSeed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            state.tasks.markDone(terminalSeed.task().value());
            Decision terminalDecision=terminalCoordinator.decide(
                    new TaskContinuityCoordinator.Request(terminalSeed.task().value(),
                            terminalSeed.run().value(),terminalSegment,1,0,
                            SegmentTerminalReason.ITERATION_EXHAUSTED,terminalSeed.guard()));
            assertTrue(terminalDecision.eligibilityFailures().contains(
                    "TASK_TERMINAL_OR_NOT_RUNNING"));

            Seed staleSeed=seed(state,config(Config.MODE_ON_REQUEST,2));
            TaskContinuityCoordinator staleCoordinator=coordinatorFor(state,complete);
            int staleSegment=staleCoordinator.beginInitialSegment(staleSeed.task().value(),
                    staleSeed.run().value(),1,0,staleSeed.guard());
            staleSeed.guard().saveCheckpoint(0,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            var lease=state.r1aDurable.readOwnership(staleSeed.task()).orElseThrow();
            FencedExecutionGuard expired=new FencedExecutionGuard(state,
                    TaskExecutionAuthority.initial(lease),java.time.Duration.ofSeconds(30),
                    ()->lease.expiresAt().plusSeconds(1));
            Decision staleDecision=staleCoordinator.decide(
                    new TaskContinuityCoordinator.Request(staleSeed.task().value(),
                            staleSeed.run().value(),staleSegment,1,0,
                            SegmentTerminalReason.ITERATION_EXHAUSTED,expired));
            assertTrue(staleDecision.eligibilityFailures().contains("EXECUTION_AUTHORITY_INVALID"));
        }
    }

    private CodingTaskRunner runner(StateStore state,ModelProvider planning,
            ModelProvider execution,ToolRegistry tools,Config config,
            BudgetEvidenceSource budget)throws Exception{
        return baseRunner(state,planning,execution,tools,config)
                .withTaskContinuityPolicy(TaskContinuityPolicy.acceptanceTestPolicy(),budget);
    }

    private CodingTaskRunner baseRunner(StateStore state,ModelProvider planning,
            ModelProvider execution,ToolRegistry tools,Config config)throws Exception{
        return new CodingTaskRunner(state,new PlanService(planning,state.plans),
                new VerificationService(workspace,state.verifications,
                        new PersistenceSanitizer(),30,50_000),
                taskId->new AgentLoop(execution,tools,config.effectiveMaxIterations(),
                        new ApprovalPolicy(config,taskId),null,state,taskId,"mock","model"),
                config,new PersistenceSanitizer(),null,null,RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP,(taskId,runId)->false,
                new InstructionManifestRuntime(state,workspace),
                (revision,summary)->new PlanApprovalPort.Decision(true,"USER_CONFIRMED",
                        "continuity-test","continuity-v1"));
    }

    private BudgetEvidenceSource budget(AtomicInteger planCalls,AtomicInteger executionCalls,
            StateStore state){
        return (task,run)->{
            try{
                int turns=planCalls.get()+executionCalls.get();
                int tools=state.toolCalls.findByTask(task).size();
                return new BudgetEvidence(true,turns,tools,turns*10L,turns*10L,
                        turns*5L,List.of());
            }catch(Exception error){return BudgetEvidence.unavailable("TEST_EVIDENCE_READ_FAILED");}
        };
    }

    private TaskContinuityCoordinator coordinatorFor(StateStore state,
            BudgetEvidenceSource source){
        return new TaskContinuityCoordinator(state,
                new TaskContinuityPolicy(true,1,2,3,3,100,100,1000),source);
    }

    private Seed seed(StateStore state,Config config)throws Exception{
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        Instant now=Instant.now();
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                config,RuntimeManifest.disabled(),now);
        assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                state.r1aDurable.acquireOwnership(task,"continuity-test",root.initialRun().id(),
                        "lease-"+java.util.UUID.randomUUID(),now,now.plusSeconds(120)));
        var lease=state.r1aDurable.readOwnership(task).orElseThrow();
        return new Seed(task,root.initialRun().id(),FencedExecutionGuard.system(state,
                TaskExecutionAuthority.initial(lease)));
    }

    private Config config(String approvalMode,int maxIterations){
        return new Config(workspace.toString(),30,50_000,200_000,approvalMode,10_000,
                Config.PROVIDER_MOCK,"model","","","system",maxIterations,"",30,
                50_000,1,true,false,false);
    }

    private long count(String table)throws Exception{
        try(var database=new com.paicli.state.DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=database.getConnection().createStatement();
                var row=statement.executeQuery("SELECT count(*) FROM "+table)){
            return row.next()?row.getLong(1):0;
        }
    }

    private record Seed(CanonicalId task,CanonicalId run,FencedExecutionGuard guard){ }
}
