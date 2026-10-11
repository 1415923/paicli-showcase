package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult;
import com.paicli.state.VerificationStore;
import com.paicli.state.StateStore;
import com.paicli.state.SteeringModels.Provenance;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeFencingIntegrationTest {
    @TempDir Path workspace;
    private static final Instant AT=Instant.parse("2026-07-17T00:00:00Z");

    @Test
    void providerInFlightTakeoverRejectsOldRuntimeBeforeToolAndAuthorityWrites()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);
                StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,
                    runtimeA.executionAuthority(),Duration.ofSeconds(30),clock);
            AtomicReference<ResumeRunAdmissionResult.Success> runtimeB=new AtomicReference<>();
            AtomicInteger providerCalls=new AtomicInteger();AtomicInteger toolCalls=new AtomicInteger();
            ToolRegistry tools=new ToolRegistry();tools.register(countingTool(toolCalls));
            var provider=(com.paicli.provider.ModelProvider)(messages,definitions)->{
                providerCalls.incrementAndGet();clock.set(AT.plusSeconds(32));
                DurableRun takeover=new DurableRun(CanonicalId.newId(EntityFamily.RUN),seed.task(),
                        seed.thread(),"RESUME",runtimeA.resumeRun().id(),seed.manifestId(),
                        seed.manifestHash(),clock.now(),null);
                try{
                    runtimeB.set(assertInstanceOf(ResumeRunAdmissionResult.Success.class,
                            stateB.r1aDurable.admitResumeRun(takeover,"runtime-b","lease-b",
                                    clock.now(),clock.now().plusSeconds(30),Duration.ZERO)));
                }catch(Exception e){throw new IllegalStateException(e);}
                return ModelResponse.toolCallResponse("",new ToolCall("write",Map.of()));
            };
            AgentLoop loop=new AgentLoop(provider,tools,2,null,null,state,seed.task().value(),
                    "mock","model-a").bindExecutionGuard(guardA);

            FencedExecutionGuard.AuthorityLostException lost=assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    ()->loop.runSegment(new AgentSegmentRequest("goal",List.of(),null,0,1)));
            assertTrue(lost.code().startsWith("EXECUTION_AUTHORITY_"));
            assertEquals(1,providerCalls.get());assertEquals(0,toolCalls.get());
            assertTrue(state.toolCalls.findByTask(seed.task().value()).isEmpty());
            assertTrue(state.snapshots.findByTask(seed.task().value()).isEmpty());
            assertTrue(state.verifications.findLatestByTask(seed.task().value()).isEmpty());
            assertTrue(state.r1aDurable.findRecoveryBlockingOccurrences(seed.task(),
                    runtimeA.resumeRun().id(),"TOOL").isEmpty());
            assertNotEquals("SEGMENT_COMPLETE",
                    state.checkpoints.loadLatest(seed.task().value()).orElseThrow().status());

            ResumeRunAdmissionResult.Success admittedB=runtimeB.get();assertNotNull(admittedB);
            FencedExecutionGuard guardB=new FencedExecutionGuard(stateB,
                    TaskExecutionAuthority.recovery(admittedB.ownership()),
                    Duration.ofSeconds(30),new ManualTime(AT.plusSeconds(33)));
            assertDoesNotThrow(()->guardB.heartbeat("RUNTIME_B_WRITE"));
            guardB.prepareTaskForResume();
            AgentLoop loopB=new AgentLoop((messages,definitions)->
                    ModelResponse.finalAnswer("runtime-b"),new ToolRegistry(),2,null,null,
                    stateB,seed.task().value(),"mock","model-a").bindExecutionGuard(guardB);
            assertTrue(loopB.runSegment(new AgentSegmentRequest("goal",
                    List.of(AgentMessage.userGoal("goal")),null,1,1)).completed());
            assertEquals("SEGMENT_COMPLETE",state.checkpoints.loadLatest(
                    seed.task().value()).orElseThrow().status());
            assertEquals(OwnershipResult.FENCING_REJECTED,
                    state.r1aDurable.validateFencing(seed.task(),"lease-a",
                            runtimeA.ownership().fencingToken(),AT.plusSeconds(33)));
            assertEquals(admittedB.run().id(),
                    state.r1aDurable.readContextPointer(seed.task()).orElseThrow().ownerRunId());
        }
    }

    @Test
    void typedAuthorityRejectsOwnerRunTokenFenceVersionAndExpiryWithoutLeakingToken()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state);
            TaskRecoveryService.Success recovery=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","secret-lease-token",AT)));
            TaskExecutionAuthority authority=recovery.executionAuthority();
            assertFalse(authority.toString().contains("secret-lease-token"));
            assertFalse(recovery.ownership().toString().contains("secret-lease-token"));
            assertFalse(recovery.toString().contains("secret-lease-token"));
            assertFalse(lease("runtime-a","secret-lease-token",AT).toString()
                    .contains("secret-lease-token"));
            assertFalse(authority.snapshot().toString().contains("secret-lease-token"));
            FencedExecutionGuard expiredGuard=new FencedExecutionGuard(state,authority,
                    Duration.ofSeconds(30),new ManualTime(authority.expiresAt()));
            FencedExecutionGuard.AuthorityLostException expired=assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,
                    ()->expiredGuard.updateTaskIterations(2));
            assertFalse(expired.getMessage().contains("secret-lease-token"));
            assertEquals(OwnershipResult.OWNERSHIP_CONFLICT,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),"other",
                            authority.runId(),authority.leaseToken(),authority.fencingToken(),
                            authority.casVersion(),AT.plusSeconds(1)));
            assertEquals(OwnershipResult.OWNERSHIP_CONFLICT,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),authority.ownerInstanceId(),
                            seed.initialRun().id(),authority.leaseToken(),authority.fencingToken(),
                            authority.casVersion(),AT.plusSeconds(1)));
            assertEquals(OwnershipResult.FENCING_REJECTED,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),authority.ownerInstanceId(),
                            authority.runId(),"wrong-token",authority.fencingToken(),
                            authority.casVersion(),AT.plusSeconds(1)));
            assertEquals(OwnershipResult.FENCING_REJECTED,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),authority.ownerInstanceId(),
                            authority.runId(),authority.leaseToken(),authority.fencingToken()+1,
                            authority.casVersion(),AT.plusSeconds(1)));
            assertEquals(OwnershipResult.VERSION_CONFLICT,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),authority.ownerInstanceId(),
                            authority.runId(),authority.leaseToken(),authority.fencingToken(),
                            authority.casVersion()+1,AT.plusSeconds(1)));
            assertEquals(OwnershipResult.EXPIRED,
                    state.r1aDurable.validateExecutionAuthority(seed.task(),authority.ownerInstanceId(),
                            authority.runId(),authority.leaseToken(),authority.fencingToken(),
                            authority.casVersion(),authority.expiresAt()));
        }
    }

    @Test
    void takeoverAfterOuterValidationRejectsAtomicWritesAndNewFenceSucceeds()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);
                StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),clock);
            guardA.createCodingRun("verify",1,1);
            guardA.validate("TEST_VALIDATION_PASSED");

            clock.set(AT.plusSeconds(31));
            DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),seed.task(),
                    seed.thread(),"RESUME",runtimeA.resumeRun().id(),seed.manifestId(),
                    seed.manifestHash(),clock.now(),null);
            ResumeRunAdmissionResult.Success runtimeB=assertInstanceOf(
                    ResumeRunAdmissionResult.Success.class,stateB.r1aDurable.admitResumeRun(next,
                            "runtime-b","lease-b",clock.now(),clock.now().plusSeconds(30),
                            Duration.ZERO));

            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->guardA.updateLastIteration(7));
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->guardA.saveCheckpoint(7,"SEGMENT_COMPLETE",
                            List.of(AgentMessage.userGoal("stale"))));
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->guardA.recordPlan("stale-plan","stale"));
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->guardA.startVerification("stale-verification",0,"verify",
                            VerificationStore.STATUS_RUNNING,WorkspaceCandidateDigest.KIND,
                            "a".repeat(64),null));
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->guardA.cancel(null,false,"stale cancellation"));
            assertEquals(1,state.checkpoints.loadLatest(seed.task().value()).orElseThrow().iteration());
            assertTrue(state.plans.findById("stale-plan").isEmpty());
            assertTrue(state.verifications.findById("stale-verification").isEmpty());

            FencedExecutionGuard guardB=new FencedExecutionGuard(stateB,
                    TaskExecutionAuthority.recovery(runtimeB.ownership()),Duration.ofSeconds(30),
                    new ManualTime(AT.plusSeconds(32)));
            guardB.createCodingRun("verify",1,1);
            guardB.updateLastIteration(2);
            guardB.saveCheckpoint(2,"SEGMENT_COMPLETE",List.of(AgentMessage.userGoal("current")));
            guardB.startVerification("current-verification",0,"verify",
                    VerificationStore.STATUS_RUNNING,WorkspaceCandidateDigest.KIND,
                    "b".repeat(64),null);
            guardB.finishVerification("current-verification",VerificationStore.STATUS_PASSED,0,
                    "ok",null,1);
            assertEquals(2,state.checkpoints.loadLatest(seed.task().value()).orElseThrow().iteration());
            assertEquals(VerificationStore.STATUS_PASSED,state.verifications
                    .findById("current-verification").orElseThrow().status());
        }
    }

    @Test
    void takeoverBetweenHeartbeatAndOccurrenceAdmissionLeavesNoOccurrenceOrToolExecution()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);
                StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),clock);
            guardA.heartbeat("TEST_HEARTBEAT_PASSED");
            clock.set(AT.plusSeconds(32));
            DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),seed.task(),
                    seed.thread(),"RESUME",runtimeA.resumeRun().id(),seed.manifestId(),
                    seed.manifestHash(),clock.now(),null);
            ResumeRunAdmissionResult.Success runtimeB=assertInstanceOf(
                    ResumeRunAdmissionResult.Success.class,stateB.r1aDurable.admitResumeRun(next,
                            "runtime-b","lease-b",clock.now(),clock.now().plusSeconds(30),
                            Duration.ZERO));
            AtomicInteger toolCalls=new AtomicInteger();
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,()->{
                guardA.admitOccurrence(2,new ToolCall("write",Map.of()));
                toolCalls.incrementAndGet();
            });
            assertEquals(0,toolCalls.get());
            assertTrue(state.r1aDurable.findRecoveryBlockingOccurrences(seed.task(),
                    runtimeA.resumeRun().id(),"TOOL").isEmpty());

            FencedExecutionGuard guardB=new FencedExecutionGuard(stateB,
                    TaskExecutionAuthority.recovery(runtimeB.ownership()),Duration.ofSeconds(30),
                    new ManualTime(AT.plusSeconds(33)));
            CanonicalId admitted=guardB.admitOccurrence(2,new ToolCall("write",Map.of()));
            guardB.completeOccurrence(admitted,ToolResult.success("ok"));
            assertEquals("COMPLETED",state.r1aDurable.findOccurrence(admitted)
                    .orElseThrow().status());
        }
    }

    @Test
    void agentLoopValidationToMutationWindowIsClosedByAtomicStoreFence()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            BlockingCoordinator coordinator=new BlockingCoordinator("EVENT_WRITE");
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),clock,coordinator);
            int eventsBefore=state.events.findMessages(seed.task().value()).size();
            AgentLoop loop=new AgentLoop((messages,definitions)->ModelResponse.finalAnswer("stale"),
                    new ToolRegistry(),2,null,null,state,seed.task().value(),"mock","model-a")
                    .bindExecutionGuard(guardA);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> attempt=executor.submit(()->capture(()->loop.runSegment(
                        new AgentSegmentRequest("goal",List.of(),null,0,1))));
                coordinator.awaitReached();clock.set(AT.plusSeconds(32));
                ResumeRunAdmissionResult.Success runtimeB=takeover(stateB,seed,
                        runtimeA.resumeRun().id(),clock.now());
                coordinator.release();
                assertInstanceOf(FencedExecutionGuard.AuthorityLostException.class,
                        attempt.get(5,TimeUnit.SECONDS));
                assertEquals(eventsBefore,state.events.findMessages(seed.task().value()).size());
                assertEquals(runtimeB.run().id(),state.r1aDurable.readContextPointer(seed.task())
                        .orElseThrow().ownerRunId());
            }finally{coordinator.release();executor.shutdownNow();}
        }
    }

    @Test
    void agentLoopHeartbeatToOccurrenceWindowRejectsOldRuntimeBeforeTool()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            BlockingCoordinator coordinator=new BlockingCoordinator("OCCURRENCE_ADMISSION");
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),clock,coordinator);
            AtomicInteger toolCalls=new AtomicInteger();ToolRegistry tools=new ToolRegistry();
            tools.register(countingTool(toolCalls));
            AgentLoop loop=new AgentLoop((messages,definitions)->ModelResponse.toolCallResponse("",
                    new ToolCall("write",Map.of())),tools,2,null,null,state,seed.task().value(),
                    "mock","model-a").bindExecutionGuard(guardA);
            int occurrencesBefore=state.r1aDurable.findRecoveryBlockingOccurrences(seed.task(),
                    runtimeA.resumeRun().id(),"TOOL").size();
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> attempt=executor.submit(()->capture(()->loop.runSegment(
                        new AgentSegmentRequest("goal",List.of(),null,0,1))));
                coordinator.awaitReached();clock.set(AT.plusSeconds(32));
                takeover(stateB,seed,runtimeA.resumeRun().id(),clock.now());
                coordinator.release();
                assertInstanceOf(FencedExecutionGuard.AuthorityLostException.class,
                        attempt.get(5,TimeUnit.SECONDS));
                assertEquals(0,toolCalls.get());
                assertEquals(occurrencesBefore,state.r1aDurable.findRecoveryBlockingOccurrences(
                        seed.task(),runtimeA.resumeRun().id(),"TOOL").size());
            }finally{coordinator.release();executor.shutdownNow();}
        }
    }

    @Test
    void startedVerificationBlocksTakeoverAndExpiredOwnerCannotLaunchProcess()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace);StateStore stateB=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            BlockingCoordinator coordinator=new BlockingCoordinator("EXTERNAL_EFFECT_VERIFICATION");
            FencedExecutionGuard guardA=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),clock,coordinator);
            guardA.createCodingRun("verify",1,1);
            AtomicInteger launches=new AtomicInteger();
            VerificationService verification=new VerificationService(workspace,state.verifications,
                    new PersistenceSanitizer(),5,1024,(path,command)->{
                        launches.incrementAndGet();throw new AssertionError("must not launch");
                    });
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> attempt=executor.submit(()->capture(()->verification.verify(
                        "verify-latched",runtimeA.resumeRun().id().value(),seed.task().value(),0,
                        "verify",guardA)));
                coordinator.awaitReached();
                Instant takeoverAt=AT.plusSeconds(32);
                DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),seed.task(),
                        seed.thread(),"RESUME",runtimeA.resumeRun().id(),seed.manifestId(),
                        seed.manifestHash(),takeoverAt,null);
                ResumeRunAdmissionResult.Rejected rejected=assertInstanceOf(
                        ResumeRunAdmissionResult.Rejected.class,stateB.r1aDurable.admitResumeRun(next,
                                "runtime-b","lease-b",takeoverAt,takeoverAt.plusSeconds(30),
                                Duration.ZERO));
                assertEquals(ResumeRunAdmissionResult.Reason.OCCURRENCE_UNRESOLVED,
                        rejected.reason());
                clock.set(takeoverAt);coordinator.release();
                assertInstanceOf(FencedExecutionGuard.AuthorityLostException.class,
                        attempt.get(5,TimeUnit.SECONDS));
                assertEquals(0,launches.get());
                assertEquals(VerificationStore.STATUS_RUNNING,state.verifications
                        .findById("verify-latched").orElseThrow().status());
                assertEquals("STARTED",state.r1aDurable.findRecoveryBlockingOccurrences(seed.task(),
                        runtimeA.resumeRun().id(),"VERIFICATION").getFirst().status());
            }finally{coordinator.release();executor.shutdownNow();}
        }
    }

    @Test
    void queuedSteeringAtomicallyPausesVerificationAndOccurrenceBeforeProcessLaunch()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtime=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guard=new FencedExecutionGuard(state,runtime.executionAuthority(),
                    Duration.ofSeconds(30),clock);
            guard.createCodingRun("verify",1,1);
            var steering=state.steerings.admit(
                    state.steerings.captureTarget(seed.task(),runtime.resumeRun().id()).target(),
                    new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",clock.now());
            AtomicInteger launches=new AtomicInteger();
            VerificationService verification=new VerificationService(workspace,state.verifications,
                    new PersistenceSanitizer(),5,1024,(path,command)->{
                        launches.incrementAndGet();throw new AssertionError("must not launch");
                    });
            VerificationResult result=verification.verify("verify-steering-paused",
                    runtime.resumeRun().id().value(),seed.task().value(),0,"verify",guard);
            assertEquals(VerificationStore.STATUS_PAUSED_BY_STEERING,result.status());
            assertEquals(0,launches.get());
            assertEquals(VerificationStore.STATUS_ERROR,state.verifications
                    .findById("verify-steering-paused").orElseThrow().status());
            assertTrue(state.verifications.wasPausedBySteering("verify-steering-paused"));
            assertTrue(state.r1aDurable.findRecoveryBlockingOccurrences(
                    seed.task(),runtime.resumeRun().id(),"VERIFICATION").isEmpty());
            var applied=new SteeringSafePointCoordinator(state,guard,()->false).check(
                    SteeringSafePointCoordinator.Candidate.VERIFICATION_FACTS_COMMITTED);
            assertEquals(com.paicli.state.SteeringStore.Status.SUCCESS,applied.status());
            assertTrue(applied.applied());
            assertEquals(com.paicli.state.SteeringModels.State.APPLIED,state.steerings
                    .readQueue(steering.steeringId()).orElseThrow().state());
        }
    }

    @Test
    void commandOccurrencesThenReadBatchAdmitEachSiblingAgainstRefreshedAuthority()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtime=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guard=new FencedExecutionGuard(state,runtime.executionAuthority(),
                    Duration.ofSeconds(30),clock);
            AtomicInteger commandCalls=new AtomicInteger();
            AtomicInteger readCalls=new AtomicInteger();
            List<Long> executionVersions=new java.util.ArrayList<>();
            ToolRegistry tools=new ToolRegistry();
            tools.register(new Tool(){
                public String name(){return "run_command";}
                public String description(){return "command";}
                public ToolDefinition toDefinition(){return new ToolDefinition(
                        name(),description(),new ObjectMapper().createObjectNode());}
                public ToolResult execute(ToolCall call){executionVersions.add(
                        guard.current().casVersion());return commandCalls.incrementAndGet()==1
                                ?ToolResult.error("exit 1"):ToolResult.success("ok");}
            });
            tools.register(new Tool(){
                public String name(){return "read_file";}
                public String description(){return "read";}
                public ToolDefinition toDefinition(){return new ToolDefinition(
                        name(),description(),new ObjectMapper().createObjectNode());}
                public ToolResult execute(ToolCall call){executionVersions.add(
                        guard.current().casVersion());readCalls.incrementAndGet();
                    return ToolResult.success(String.valueOf(call.arguments().get("path")));}
            });
            AtomicInteger providerCalls=new AtomicInteger();
            var provider=(com.paicli.provider.ModelProvider)(messages,definitions)->switch(
                    providerCalls.getAndIncrement()){
                case 0->ModelResponse.toolCallResponse("first command",
                        new ToolCall("run_command",Map.of("command","first"),"command-1"));
                case 1->ModelResponse.toolCallResponse("second command",
                        new ToolCall("run_command",Map.of("command","second"),"command-2"));
                case 2->ModelResponse.toolCallBatchResponse("read files",List.of(
                        new ToolCall("read_file",Map.of("path","notes.txt"),"read-1"),
                        new ToolCall("read_file",Map.of("path","README.md"),"read-2"),
                        new ToolCall("read_file",Map.of("path","docs/guide.md"),"read-3")),
                        com.paicli.provider.ProviderResponseEnvelope.inspect(
                                List.of("read-1","read-2","read-3"),false),false);
                default->ModelResponse.finalAnswer("done");
            };
            AgentLoop loop=new AgentLoop(provider,tools,7,null,null,state,seed.task().value(),
                    "mock","model-a").bindExecutionGuard(guard);

            assertTrue(loop.runSegment(new AgentSegmentRequest(
                    "goal",List.of(),null,0,7)).completed());

            assertEquals(2,commandCalls.get());
            assertEquals(3,readCalls.get());
            assertEquals(5,executionVersions.size());
            for(int index=1;index<executionVersions.size();index++)
                assertTrue(executionVersions.get(index)>executionVersions.get(index-1),
                        "every sibling must execute under a refreshed authority version");
            String run=runtime.resumeRun().id().value();
            assertEquals("FAILED",state.r1aDurable.findOccurrenceByIdentity(seed.task(),
                    "recovery-tool:"+run+":1").orElseThrow().status());
            assertEquals("COMPLETED",state.r1aDurable.findOccurrenceByIdentity(seed.task(),
                    "recovery-tool:"+run+":2").orElseThrow().status());
            assertEquals("command-1",state.r1aDurable.findOccurrenceByIdentity(seed.task(),
                    "recovery-tool:"+run+":1").orElseThrow().toolCallId());
            assertEquals("command-2",state.r1aDurable.findOccurrenceByIdentity(seed.task(),
                    "recovery-tool:"+run+":2").orElseThrow().toolCallId());
            for(int ordinal=1;ordinal<=3;ordinal++){
                var occurrence=state.r1aDurable.findOccurrenceByIdentity(seed.task(),
                        "recovery-tool:"+run+":3:"+ordinal).orElseThrow();
                assertEquals("3",occurrence.iterationId());
                assertEquals("read-"+ordinal,occurrence.toolCallId());
                assertEquals("COMPLETED",occurrence.status());
            }
        }
    }

    @Test
    void duplicateBatchSiblingOrdinalStillFailsClosedAtOccurrenceAdmission()throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        try(StateStore state=new StateStore(workspace)){
            Seed seed=seed(state);ManualTime clock=new ManualTime(AT.plusSeconds(1));
            TaskRecoveryService.Success runtime=assertInstanceOf(TaskRecoveryService.Success.class,
                    recovery(state).recover(seed.task(),config(),RuntimeManifest.disabled(),10_000,
                            lease("runtime-a","lease-a",AT)));
            FencedExecutionGuard guard=new FencedExecutionGuard(state,runtime.executionAuthority(),
                    Duration.ofSeconds(30),clock);
            CanonicalId first=guard.admitOccurrence(7,1,
                    new ToolCall("read_file",Map.of("path","README.md")));
            guard.completeOccurrence(first,ToolResult.success("readme"));

            FencedExecutionGuard.AuthorityLostException rejected=assertThrows(
                    FencedExecutionGuard.AuthorityLostException.class,()->guard.admitOccurrence(
                            7,1,new ToolCall("read_file",Map.of("path","notes.txt"))));
            assertEquals("EXECUTION_MUTATION_INTEGRITY_VIOLATION",rejected.code());
            assertEquals("OCCURRENCE_ADMISSION",rejected.boundary());
        }
    }

    private Seed seed(StateStore state)throws Exception{
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                config(),RuntimeManifest.disabled(),AT.minusSeconds(10));
        state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("goal")));
        return new Seed(task,root.threadId(),root.initialRun(),
                root.instructions().manifest().id(),root.instructions().manifest().canonicalHash());
    }

    private TaskRecoveryService recovery(StateStore state){
        return new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace));
    }
    private Config config(){return new Config(workspace.toString(),30,50_000,200_000,
            Config.MODE_WORKSPACE_WRITE,10_000,Config.PROVIDER_MOCK,"model-a","","",
            "system",3,"verify",120,50_000,1,false,true,false);}
    private static TaskRecoveryService.LeaseRequest lease(String owner,String token,Instant at){
        return new TaskRecoveryService.LeaseRequest(owner,token,at,at.plusSeconds(30),Duration.ZERO);
    }
    private ResumeRunAdmissionResult.Success takeover(StateStore state,Seed seed,
            CanonicalId predecessor,Instant at)throws Exception{
        DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),seed.task(),seed.thread(),
                "RESUME",predecessor,seed.manifestId(),seed.manifestHash(),at,null);
        return assertInstanceOf(ResumeRunAdmissionResult.Success.class,
                state.r1aDurable.admitResumeRun(next,"runtime-b","lease-b",at,
                        at.plusSeconds(30),Duration.ZERO));
    }
    private static Throwable capture(ThrowingAction action){
        try{action.run();return null;}catch(Throwable failure){return failure;}
    }
    private static Tool countingTool(AtomicInteger calls){return new Tool(){
        public String name(){return "write";}public String description(){return "count";}
        public ToolDefinition toDefinition(){return new ToolDefinition("write","count",
                new ObjectMapper().createObjectNode());}
        public ToolResult execute(ToolCall call){calls.incrementAndGet();return ToolResult.success("ok");}
    };}
    private record Seed(CanonicalId task,CanonicalId thread,DurableRun initialRun,
                        String manifestId,String manifestHash){ }
    private static final class ManualTime implements FencedExecutionGuard.TimeSource{
        private volatile Instant now;private ManualTime(Instant now){this.now=now;}
        public Instant now(){return now;}private void set(Instant now){this.now=now;}
    }
    private static final class BlockingCoordinator
            implements FencedExecutionGuard.TestCoordinator{
        private final String boundary;private final AtomicBoolean armed=new AtomicBoolean(true);
        private final CountDownLatch reached=new CountDownLatch(1);
        private final CountDownLatch released=new CountDownLatch(1);
        private BlockingCoordinator(String boundary){this.boundary=boundary;}
        public void before(String actual){
            if(!boundary.equals(actual)||!armed.compareAndSet(true,false))return;
            reached.countDown();
            try{if(!released.await(5,TimeUnit.SECONDS))throw new AssertionError(
                    "Timed out waiting to release "+boundary);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        }
        private void awaitReached()throws InterruptedException{
            assertTrue(reached.await(5,TimeUnit.SECONDS),"Runtime did not reach "+boundary);
        }
        private void release(){released.countDown();}
    }
    @FunctionalInterface private interface ThrowingAction{void run()throws Exception;}
}
