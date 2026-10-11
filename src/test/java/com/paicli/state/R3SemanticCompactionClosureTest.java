package com.paicli.state;

import com.paicli.context.ContextModels.*;
import com.paicli.context.ContextualModelProvider;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class R3SemanticCompactionClosureTest {
    @TempDir Path workspace;

    @Test void realAuthoritiesRemainProviderVisibleThroughStructuredCompaction()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            var authority=admitted.guard().current().snapshot();
            enableVerification(workspace,root.initialRun().id().value());
            Revision revision=activatePlan(state,authority);
            var started=state.planExecutions.startNextReadyAttempt(authority,Instant.now(),1,"r3-reset");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());

            var messages=new ArrayList<AgentMessage>();
            AgentMessage user=AgentMessage.userGoal("durable user intent");
            admitted.guard().appendEvent(0,user);messages.add(user);
            for(int i=0;i<8;i++){
                AgentMessage result=AgentMessage.finalAnswer(
                        "durable assistant result "+i+" "+"x".repeat(7500));
                admitted.guard().appendEvent(i+1,result);messages.add(result);
            }
            CanonicalId occurrence=admitPlannedRead(state,authority,revision,
                    started.value().attempt().attemptNumber(),1,"tool-call-1","evidence.txt");
            admitted.guard().recordToolCall(1,"tool-call-1","read_file","Authorization: secret",
                    ToolResult.success("raw file body must not be persisted"));
            admitted.guard().completeOccurrence(occurrence,ToolResult.success("allowlisted success"));
            admitted.guard().recordSnapshot("snapshot-1","tool-call-1","private.txt",
                    "protected/snapshot-1","CREATE",false,0,"a".repeat(64));
            admitted.guard().transitionSnapshot("snapshot-1","CAPTURED","APPLIED","b".repeat(64));

            assertEquals(PlanExecutionStore.Status.SUCCESS,state.planExecutions.completeAttempt(authority,
                    Instant.now(),started.value().attempt(),Outcome.SUCCEEDED,10,
                    started.value().step().transitionVersion(),null,"done").status());
            admitted.guard().startVerification("verification-passed",0,"verify",
                    VerificationStore.STATUS_RUNNING,"WORKSPACE_CONTENT_SHA256_V1",
                    "a".repeat(64),null);
            admitted.guard().finishVerification("verification-passed",VerificationStore.STATUS_PASSED,
                    0,"bounded",null,1);
            admitted.guard().startVerification("verification-failed",1,"verify",
                    VerificationStore.STATUS_RUNNING,"WORKSPACE_CONTENT_SHA256_V1",
                    "b".repeat(64),null);
            admitted.guard().finishVerification("verification-failed",VerificationStore.STATUS_FAILED,
                    1,null,"bounded blocker",1);
            admitted.guard().startRepair("repair-1",1,"verification-failed","still unresolved");
            admitted.guard().finishRepair("repair-1",RepairAttemptStore.STATUS_FAILED);

            Identity identity=identity(workspace,root,revision.planRevisionId().value());
            ContextInvocation invocation=invocation(identity);
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(invocation,
                    "runtime",List.copyOf(messages),List.of(),admitted.guard().current()));
            assertKinds(assembled.sources(),List.of("PLAN_STEP","ATTEMPT","EVIDENCE",
                    "COMPLETION_GATE","TOOL_OCCURRENCE","TOOL_RESULT","ARTIFACT_REFERENCE",
                    "VERIFICATION_RESULT","REPAIR_ITERATION"));
            assertTrue(assembled.sources().sources().stream().filter(s->"EVIDENCE".equals(s.kind()))
                    .anyMatch(s->s.renderedPayload().contains("verified=true")));
            assertTrue(assembled.sources().sources().stream().filter(s->"TOOL_RESULT".equals(s.kind()))
                    .anyMatch(s->s.renderedPayload().contains("tool_name=read_file")
                            &&!s.renderedPayload().contains("raw file body")));

            CompiledContext base=new DeterministicContextCompiler().compile(new CompileRequest(
                    // Only execution metadata with an actual observation may be hidden.
                    assembled.invocation(),assembled.sources(),assembled.tools()));
            assertTrue(assembled.sources().sources().stream().filter(s->"TOOL_RESULT".equals(s.kind()))
                    .allMatch(ContextSource::providerVisible),
                    "execution facts with no durable observation are not redundant and remain visible");
            state.contexts.publishBase(base);
            var target=state.readOnlySnapshot(()->state.contexts.resolveTarget(identity.taskId(),identity.runId(),
                    OperationKind.CHAT,null,com.paicli.context.CompactTargetModels.TargetRelation.EXPLICIT_TARGET));
            assertEquals(com.paicli.context.CompactTargetModels.ResolutionStatus.PRESENT,target.status());
            var candidate=state.contexts.createOrReuseCandidate(target.identity(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            state.contexts.applyCandidate(candidate.contextId(),target.identity(),
                    admitted.guard().current().snapshot());

            AtomicInteger delegateCalls=new AtomicInteger();
            AtomicReference<List<AgentMessage>> sentMessages=new AtomicReference<>();
            AtomicReference<List<ToolDefinition>> sentTools=new AtomicReference<>();
            com.paicli.provider.ModelProvider delegate=(actualMessages,actualTools)->{
                delegateCalls.incrementAndGet();sentMessages.set(List.copyOf(actualMessages));
                sentTools.set(List.copyOf(actualTools));return ModelResponse.finalAnswer("provider-ok");};
            ContextualModelProvider provider=new ContextualModelProvider(delegate,state,workspace,
                    root.taskId().value(),"mock","model",OperationKind.CHAT,
                    new CapabilityProfile("mock",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                    false,"runtime");
            ModelResponse firstResponse=provider.chat(OperationKind.CHAT,"semantic-provider",
                    List.copyOf(messages),List.of(),admitted.guard());
            assertEquals("provider-ok",firstResponse.content());
            admitted.guard().completeOccurrence(CanonicalId.parse(firstResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            List<AgentMessage> firstTurn=sentMessages.get();
            int goalIndex=firstTurn.indexOf(user);
            assertTrue(goalIndex>0,"stable goal follows the runtime contract and current authority");
            var stablePrefix=List.copyOf(firstTurn.subList(0,goalIndex+1));
            long firstRawEvents=firstTurn.stream().filter(message->
                    message.type()==AgentMessage.MessageType.FINAL
                            &&message.content().startsWith("durable assistant result ")).count();
            long firstRawAuthorities=firstTurn.stream().filter(message->
                    message.type()==AgentMessage.MessageType.FINAL
                            &&message.content().startsWith("kind=")).count();

            ModelResponse secondResponse=provider.chat(OperationKind.CHAT,
                    "semantic-provider-second",List.copyOf(messages),List.of(),admitted.guard());
            assertEquals("provider-ok",secondResponse.content());
            admitted.guard().completeOccurrence(CanonicalId.parse(secondResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            assertEquals(2,delegateCalls.get());
            List<AgentMessage> secondTurn=sentMessages.get();
            assertEquals(firstRawEvents,secondTurn.stream().filter(message->
                    message.type()==AgentMessage.MessageType.FINAL
                            &&message.content().startsWith("durable assistant result ")).count(),
                    "BASE-over-COMPACTED must not reintroduce selected durable EVENT rows");
            assertEquals(firstRawAuthorities,secondTurn.stream().filter(message->
                    message.type()==AgentMessage.MessageType.FINAL
                            &&message.content().startsWith("kind=")).count(),
                    "BASE-over-COMPACTED must retain recursive semantic-source suppression");
            var active=state.contexts.pointer(identity.taskId(),identity.runId(),OperationKind.CHAT).orElseThrow();
            var generation=state.contexts.find(active.contextGenerationId()).orElseThrow();
            assertEquals(Lifecycle.READY,generation.lifecycle());
            String recomputed=DeterministicContextCompiler.hash(
                    DeterministicContextCompiler.segmentSetHash(state.contexts.segments(active.contextGenerationId()))
                            +"|"+DeterministicContextCompiler.compiledHash(sentMessages.get(),sentTools.get()));
            assertEquals(generation.compiledHash(),recomputed,
                    "stored sanitized segments and the exact delegate material close the production hash contract");
            String visible=sentMessages.get().stream().map(AgentMessage::content)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(visible.contains("R3_COMPACTED_LEDGER_V4"));
            assertTrue(visible.contains("\"source_id\""));assertTrue(visible.contains("\"source_revision\""));
            assertTrue(visible.contains("\"authority_provenance\""));
            assertTrue(visible.contains("verification-passed"));assertTrue(visible.contains("verified=true"));
            assertTrue(visible.contains("tool_name=read_file"));assertTrue(visible.contains("snapshot-1"));
            assertTrue(visible.contains("verification-failed"));assertTrue(visible.contains("repair-1"));
            assertTrue(visible.contains("UNRESOLVED"));assertFalse(visible.matches("(?s).*segment_kind#[0-9a-f]{64}.*"));
            assertTrue(java.util.stream.IntStream.range(0,8).filter(i->
                    visible.contains("durable assistant result "+i)).count()>=4,
                    "versioned recent-history minimum must reach the actual Provider unchanged");
            assertFalse(visible.contains("Authorization: secret"));
            assertFalse(visible.contains("raw file body must not be persisted"));

            for(int index=0;index<3;index++){
                AgentMessage chained=AgentMessage.finalAnswer(
                        "post-compact durable history "+index+" "+"y".repeat(7_500));
                admitted.guard().appendEvent(20+index,chained);
                messages.add(chained);
                ModelResponse chainedResponse=provider.chat(OperationKind.CHAT,
                        "semantic-provider-chain-"+index,List.copyOf(messages),List.of(),
                        admitted.guard());
                assertEquals("provider-ok",chainedResponse.content());
                assertEquals(stablePrefix,sentMessages.get().subList(0,stablePrefix.size()),
                        "growing durable history preserves the actual Provider instruction/goal prefix");
                admitted.guard().completeOccurrence(
                        CanonicalId.parse(chainedResponse.providerOccurrenceId()),
                        ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            }
            var recompactTarget=state.readOnlySnapshot(()->state.contexts.resolveTarget(
                    identity.taskId(),identity.runId(),OperationKind.CHAT,null,
                    com.paicli.context.CompactTargetModels.TargetRelation.EXPLICIT_TARGET));
            assertEquals(com.paicli.context.CompactTargetModels.ResolutionStatus.PRESENT,
                    recompactTarget.status());
            var recompactCandidate=state.contexts.createOrReuseCandidate(
                    recompactTarget.identity(),DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            state.contexts.applyCandidate(recompactCandidate.contextId(),
                    recompactTarget.identity(),admitted.guard().current().snapshot());
            assertTimeout(Duration.ofSeconds(10),()->{
                var verified=state.contexts.verifiedActiveLedger(identity.taskId(),
                        identity.runId(),OperationKind.CHAT).orElseThrow();
                assertFalse(verified.segments().isEmpty());
                assertEquals("provider-ok",provider.chat(OperationKind.CHAT,
                        "semantic-provider-after-recompact",List.copyOf(messages),List.of(),
                        admitted.guard()).content());
            });
            assertEquals(6,delegateCalls.get(),
                    "shared compacted ancestry must reach exactly one new Provider admission");
        }
    }

    @Test void unknownToolEffectIsTypedNonCompactableAndUnsupportedCompactableFailsClosed()throws Exception{
        try(StateStore state=new StateStore(workspace.resolve("unknown"))){
            var admitted=R3ContextTestFixtures.admit(state,workspace.resolve("unknown"));var root=admitted.root();
            CanonicalId occurrence=admitted.guard().admitOccurrence(1,new ToolCall("write_file",Map.of()));
            admitted.guard().unknownOccurrence(occurrence,"unknown external effect");
            AgentMessage user=AgentMessage.userGoal("continue safely");admitted.guard().appendEvent(0,user);
            Identity identity=identity(workspace.resolve("unknown"),root,null);var assembled=state.readOnlySnapshot(()->state.contextSources
                    .assemble(invocation(identity),"runtime",List.of(user),List.of(),admitted.guard().current()));
            ContextSource unknown=assembled.sources().sources().stream().filter(source->
                    "TOOL_OCCURRENCE".equals(source.kind())).findFirst().orElseThrow();
            assertEquals(Compactability.NON_COMPACTABLE,unknown.compactability());
            assertTrue(unknown.renderedPayload().contains("status=UNKNOWN"));
            assertTrue(unknown.renderedPayload().contains("unresolved=true"));
        }
        var unsupported=List.of(new com.paicli.context.DeterministicCompactionPolicy.HistorySegment(
                0,"UNSUPPORTED_TYPED_FACT",Compactability.COMPACTABLE,1,LedgerFactKind.STRUCTURED_DERIVED));
        Budget budget=new Budget(1,0,1,1,100,LimitProvenance.CONFIGURED_LIMIT,
                EstimateProvenance.DERIVED_ESTIMATE,Budget.Decision.READY);
        assertEquals("COMPACTION_UNSUPPORTED_SOURCE",assertThrows(
                com.paicli.context.DeterministicCompactionPolicy.PolicyFailure.class,()->
                        new com.paicli.context.DeterministicCompactionPolicy().select(unsupported,budget)).getMessage());
    }

    @Test void historicalLedgerValidationDoesNotRerenderLaterPlanAttemptState()throws Exception{
        Path caseWorkspace=workspace.resolve("mutable-plan-state");
        java.nio.file.Files.createDirectories(caseWorkspace);
        try(StateStore state=new StateStore(caseWorkspace)){
            var admitted=R3ContextTestFixtures.admit(state,caseWorkspace);var root=admitted.root();
            var authority=admitted.guard().current().snapshot();
            enableVerification(caseWorkspace,root.initialRun().id().value());
            Revision revision=activatePlan(state,authority);
            var started=state.planExecutions.startNextReadyAttempt(authority,Instant.now(),1,
                    "mutable-plan-state");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
            var messages=new ArrayList<AgentMessage>();
            AgentMessage user=AgentMessage.userGoal("preserve historical attempt snapshot");
            admitted.guard().appendEvent(0,user);messages.add(user);
            for(int i=0;i<8;i++){
                AgentMessage history=AgentMessage.finalAnswer(
                        "mutable attempt history "+i+" "+"x".repeat(7_500));
                admitted.guard().appendEvent(i+1,history);messages.add(history);
            }
            Identity identity=identity(caseWorkspace,root,revision.planRevisionId().value());
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(invocation(identity),
                    "runtime",List.copyOf(messages),List.of(),admitted.guard().current()));
            CompiledContext base=new DeterministicContextCompiler().compile(new CompileRequest(
                    assembled.invocation(),assembled.sources(),assembled.tools()));
            var published=state.contexts.publishBase(base);
            var sourcePointer=new ContextGenerationStore.Pointer(identity.taskId(),identity.runId(),
                    OperationKind.CHAT,published.contextGenerationId(),published.pointerVersion());
            var candidate=state.contexts.createOrReuseAutoCandidate(sourcePointer,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,authority);
            state.contexts.applyAutoCandidate(candidate.contextId(),sourcePointer,authority);

            assertEquals(PlanExecutionStore.Status.SUCCESS,state.planExecutions.completeAttempt(authority,
                    Instant.now(),started.value().attempt(),Outcome.SUCCEEDED,1,
                    started.value().step().transitionVersion(),null,"completed after compact").status());
            assertDoesNotThrow(()->state.contexts.verifiedActiveLedger(identity.taskId(),
                    identity.runId(),OperationKind.CHAT).orElseThrow());
            AtomicReference<List<AgentMessage>> sent=new AtomicReference<>();
            com.paicli.provider.ModelProvider delegate=(actual,tools)->{
                sent.set(List.copyOf(actual));return ModelResponse.finalAnswer("continued");};
            var provider=new ContextualModelProvider(delegate,state,caseWorkspace,identity.taskId(),
                    "mock","model",OperationKind.CHAT,new CapabilityProfile("mock",131072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"runtime");
            assertEquals("continued",provider.chat(OperationKind.CHAT,"after-attempt-transition",
                    List.copyOf(messages),List.of(),admitted.guard()).content());
            assertTrue(sent.get().stream().anyMatch(message->message.content().startsWith("kind=ATTEMPT;")
                    &&message.content().contains("state=SUCCEEDED")));
        }
    }

    @Test void terminalMaintenanceCompactsFrozenSnapshotAfterRealCompletionDecisionDrift()throws Exception{
        Path caseWorkspace=workspace.resolve("maintenance-completion-drift");
        java.nio.file.Files.createDirectories(caseWorkspace);
        try(StateStore state=new StateStore(caseWorkspace)){
            MaintenanceDrift fixture=prepareMaintenanceDrift(state,caseWorkspace,"drift-pass");
            var applied=state.contexts.compactMaintenanceBundle(fixture.maintenance(),
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            assertEquals(2,applied.size());
            for(var result:applied){assertEquals(Lifecycle.READY,result.lifecycle());
                OperationKind operation=state.contexts.find(result.contextGenerationId())
                        .orElseThrow().operation();
                assertEquals(result.contextGenerationId(),state.contexts.pointer(
                        fixture.identity().taskId(),fixture.identity().runId(),operation)
                        .orElseThrow().contextGenerationId());}
            assertEquals(2,state.planCompletions.readRecovery(fixture.authority().taskId(),
                    fixture.revision().planRevisionId()).evaluationCount());
        }
    }

    @Test void terminalMaintenanceImmutableValidationRejectsCandidateTamper()throws Exception{
        Path caseWorkspace=workspace.resolve("maintenance-candidate-tamper");
        java.nio.file.Files.createDirectories(caseWorkspace);
        try(StateStore state=new StateStore(caseWorkspace)){
            MaintenanceDrift fixture=prepareMaintenanceDrift(state,caseWorkspace,"drift-tamper");
            var tampered=fixture.candidates().stream().filter(candidate->
                    candidate.operation()==OperationKind.TOOL_CONTINUATION).findFirst().orElseThrow();
            try(DatabaseManager inspection=new DatabaseManager(caseWorkspace.resolve(".paicli/state"));
                var statement=inspection.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_segment_update_immutable");
                assertTrue(statement.executeUpdate("UPDATE context_segments SET "
                        +"sanitized_payload=sanitized_payload||' tampered' WHERE context_generation_id='"
                        +tampered.contextId()+"' AND segment_kind='COMPACTED_LEDGER'")>0);
            }
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.compactMaintenanceBundle(fixture.maintenance(),
                            DeterministicContextCompiler.COMPILER_VERSION,
                            DeterministicContextCompiler.POLICY_VERSION));
            assertEquals("COMPACTION_INTEGRITY",failure.getMessage());
            assertEquals(fixture.chatBase().contextGenerationId(),state.contexts.pointer(
                    fixture.identity().taskId(),fixture.identity().runId(),OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
            assertEquals(fixture.toolBase().contextGenerationId(),state.contexts.pointer(
                    fixture.identity().taskId(),fixture.identity().runId(),OperationKind.TOOL_CONTINUATION)
                    .orElseThrow().contextGenerationId());
            for(var candidate:fixture.candidates())assertEquals(Lifecycle.PREPARED,
                    state.contexts.find(candidate.contextId()).orElseThrow().lifecycle());
        }
    }

    @Test void terminalMaintenanceRejectsStalePointerAfterCandidatePreparation()throws Exception{
        Path caseWorkspace=workspace.resolve("maintenance-stale-cas");
        java.nio.file.Files.createDirectories(caseWorkspace);
        try(StateStore state=new StateStore(caseWorkspace)){
            MaintenanceDrift fixture=prepareMaintenanceDrift(state,caseWorkspace,"drift-stale");
            Identity replacementIdentity=identity(caseWorkspace,fixture.admitted().root(),
                    fixture.revision().planRevisionId().value());
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                    invocation(replacementIdentity),"runtime",fixture.messages(),List.of(),
                    fixture.admitted().guard().current()));
            var replacement=state.contexts.publishBase(new DeterministicContextCompiler().compile(
                    new CompileRequest(assembled.invocation(),assembled.sources(),assembled.tools())));
            assertNotEquals(fixture.chatBase().contextGenerationId(),replacement.contextGenerationId());
            var chatCandidate=fixture.candidates().stream().filter(candidate->
                    candidate.operation()==OperationKind.CHAT).findFirst().orElseThrow();
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.applyMaintenanceCandidate(chatCandidate.contextId(),
                            fixture.maintenance()));
            assertEquals("COMPACTION_STALE_SOURCE",failure.getMessage());
            assertEquals(replacement.contextGenerationId(),state.contexts.pointer(
                    fixture.identity().taskId(),fixture.identity().runId(),OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }

    private MaintenanceDrift prepareMaintenanceDrift(StateStore state,Path caseWorkspace,String marker)
            throws Exception{
        var admitted=R3ContextTestFixtures.admit(state,caseWorkspace);var root=admitted.root();
        var authority=admitted.guard().current().snapshot();enableVerification(caseWorkspace,
                root.initialRun().id().value());
        Revision revision=activatePlan(state,authority);
        var started=state.planExecutions.startNextReadyAttempt(authority,Instant.now(),1,marker);
        assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
        var first=state.planCompletions.evaluateAndComplete(authority,Instant.now(),
                revision.planRevisionId());
        assertEquals(PlanCompletionModels.CompletionResult.EVIDENCE_MISSING,first.result());
        var frozenRecovery=state.planCompletions.readRecovery(authority.taskId(),
                revision.planRevisionId());
        assertEquals(1,frozenRecovery.evaluationCount());assertFalse(frozenRecovery.completed());

        var messages=new ArrayList<AgentMessage>();
        AgentMessage user=AgentMessage.userGoal("terminal maintenance immutable snapshot");
        admitted.guard().appendEvent(0,user);messages.add(user);
        for(int index=0;index<8;index++){
            AgentMessage history=AgentMessage.finalAnswer(marker+" history "+index+" "+"x".repeat(7_500));
            admitted.guard().appendEvent(index+1,history);messages.add(history);
        }
        Identity identity=identity(caseWorkspace,root,revision.planRevisionId().value());
        var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(invocation(identity),
                "runtime",List.copyOf(messages),List.of(),admitted.guard().current()));
        var chatBase=state.contexts.publishBase(new DeterministicContextCompiler().compile(
                new CompileRequest(assembled.invocation(),assembled.sources(),assembled.tools())));
        assertTrue(state.contexts.references(chatBase.contextGenerationId()).stream().anyMatch(reference->
                "COMPLETION_GATE".equals(reference.type())
                        &&"1:false:UNRESOLVED".equals(reference.revision())));

        String callId="maintenance-tool-call";
        AgentMessage action=AgentMessage.actionWithToolCall("read evidence",callId,
                "{\"name\":\"read_file\",\"arguments\":{\"path\":\"evidence.txt\"}}");
        AgentMessage observation=AgentMessage.observationForToolCall("bounded",callId);
        admitted.guard().appendEvent(9,action);messages.add(action);
        CanonicalId occurrence=admitPlannedRead(state,authority,revision,
                started.value().attempt().attemptNumber(),9,callId,"evidence.txt");
        admitted.guard().recordToolCall(9,callId,"read_file","{}",
                ToolResult.success("bounded"));
        admitted.guard().completeOccurrence(occurrence,ToolResult.success("bounded"));
        admitted.guard().appendEvent(9,observation);messages.add(observation);
        Identity toolIdentity=identity(caseWorkspace,root,revision.planRevisionId().value());
        var toolAssembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                invocation(toolIdentity,OperationKind.TOOL_CONTINUATION),"runtime",
                List.copyOf(messages),List.of(),admitted.guard().current()));
        var toolBase=state.contexts.publishBase(new DeterministicContextCompiler().compile(
                new CompileRequest(toolAssembled.invocation(),toolAssembled.sources(),
                        toolAssembled.tools())));
        assertTrue(state.contexts.references(toolBase.contextGenerationId()).stream().anyMatch(reference->
                "COMPLETION_GATE".equals(reference.type())
                        &&"1:false:UNRESOLVED".equals(reference.revision())));
        assertEquals(PlanExecutionStore.Status.SUCCESS,state.planExecutions.completeAttempt(authority,
                Instant.now(),started.value().attempt(),Outcome.SUCCEEDED,10,
                started.value().step().transitionVersion(),null,"done").status());
        admitted.guard().startVerification("maintenance-verification",0,"verify",
                VerificationStore.STATUS_RUNNING,"WORKSPACE_CONTENT_SHA256_V1",
                "c".repeat(64),null);
        admitted.guard().finishVerification("maintenance-verification",
                VerificationStore.STATUS_PASSED,0,"passed",null,1);
        var complete=state.planCompletions.evaluateAndComplete(authority,Instant.now(),
                revision.planRevisionId());
        assertEquals(PlanCompletionModels.CompletionResult.COMPLETE,complete.result());
        var current=state.planCompletions.readRecovery(authority.taskId(),revision.planRevisionId());
        assertEquals(2,current.evaluationCount());assertTrue(current.completed());
        terminalize(caseWorkspace,identity.taskId(),identity.runId());
        var maintenance=state.contexts.captureMaintenanceAuthority("session-"+marker,
                identity.threadId(),identity.taskId(),null);
        assertEquals(List.of(OperationKind.CHAT,OperationKind.TOOL_CONTINUATION),
                maintenance.targets().stream().map(MaintenanceTarget::operation).toList());
        var candidates=new ArrayList<ContextGenerationStore.Generation>();
        for(var target:maintenance.targets())candidates.add(
                state.contexts.createOrReuseMaintenanceCandidate(maintenance,target,
                        DeterministicContextCompiler.COMPILER_VERSION,
                        DeterministicContextCompiler.POLICY_VERSION));
        assertTrue(candidates.stream().allMatch(candidate->candidate.lifecycle()==Lifecycle.PREPARED));
        return new MaintenanceDrift(admitted,authority,revision,identity,List.copyOf(messages),
                chatBase,toolBase,maintenance,List.copyOf(candidates));
    }

    private static void terminalize(Path workspace,String task,String run)throws Exception{
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"));
            var taskUpdate=database.getConnection().prepareStatement(
                    "UPDATE task_identities SET status='COMPLETED',terminal_at=? WHERE task_id=?");
            var runUpdate=database.getConnection().prepareStatement(
                    "UPDATE durable_runs SET terminal_status='COMPLETED' WHERE task_id=? AND run_id=?")){
            taskUpdate.setString(1,Instant.now().toString());taskUpdate.setString(2,task);
            assertEquals(1,taskUpdate.executeUpdate());runUpdate.setString(1,task);
            runUpdate.setString(2,run);assertEquals(1,runUpdate.executeUpdate());
        }
    }

    private record MaintenanceDrift(R3ContextTestFixtures.Admitted admitted,
            com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot authority,Revision revision,
            Identity identity,List<AgentMessage> messages,ContextGenerationStore.Published chatBase,
            ContextGenerationStore.Published toolBase,MaintenanceAuthority maintenance,
            List<ContextGenerationStore.Generation> candidates){ }

    private Revision activatePlan(StateStore state,
            com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot authority){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN),step=CanonicalId.newId(EntityFamily.PLAN_STEP);
        StepDefinition definition=new StepDefinition(step,CanonicalId.newId(EntityFamily.PLAN_STEP),0,
                "execute","bounded definition",PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL,
                        PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT),
                PlanCompletionModels.EffectClass.GENERAL,
                List.of(exactReadRequirement()),"b".repeat(64));
        Revision draft=new Revision(plan,authority.taskId(),1,"goal","accept","","","verify",
                "0".repeat(64),"USER","r3-reset",authority.runId(),Instant.now(),List.of(definition),
                List.of(),List.of(),Revision.CANONICAL_SCHEMA_V3);
        Revision revision=new Revision(plan,authority.taskId(),1,draft.goal(),draft.acceptanceCriteria(),
                draft.assumptions(),draft.risks(),draft.verificationStrategy(),PlanCanonicalizer.hash(draft),
                draft.sourceType(),draft.provenance(),authority.runId(),draft.createdAt(),draft.steps(),
                draft.dependencies(),draft.lineage(),Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(
                authority,Instant.now(),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(
                authority,Instant.now(),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(authority,
                Instant.now(),plan,1,0,"approval-r3","user","r3-reset",null).status());
        return revision;
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement() {
        String json = "{\"path\":\"evidence.txt\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement("compact-read-evidence", 0,
                "read_file", PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private CanonicalId admitPlannedRead(StateStore state,
            com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot authority,
            Revision revision,int attemptNumber,int iteration,String toolCallId,String path) {
        var step=revision.steps().getFirst();
        var requirement=step.toolRequirements().getFirst();
        ToolCall call=new ToolCall("read_file",Map.of("path",path),toolCallId);
        var request=new FencedMutationStore.PlanToolOccurrenceRequest(toolCallId,"read_file",
                PlanCompletionModels.EffectClass.READ.name(),
                requirement.authoritySelector().digest(),requirement.requirementId(),1,
                Integer.toString(iteration),"compact-tool:"+toolCallId,
                com.paicli.runtime.FencedExecutionGuard.canonicalToolCallHash(call));
        var reserved=state.fencedMutations.reservePlanToolBatch(authority,Instant.now(),
                revision.planRevisionId(),step.stepRevisionId(),attemptNumber,List.of(request));
        assertEquals(R1aStoreResults.FencedMutationResult.SUCCESS,reserved.result());
        assertEquals(1,reserved.occurrenceIds().size());
        return reserved.occurrenceIds().getFirst();
    }

    private void enableVerification(Path targetWorkspace,String runId)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(targetWorkspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement(
                    "UPDATE coding_runs SET verification_command='verify' WHERE run_id=?")){
            statement.setString(1,runId);assertEquals(1,statement.executeUpdate());
        }
    }

    private static Identity identity(Path workspace,
            com.paicli.runtime.InstructionManifestRuntime.RootAdmission root,String plan){
        return new Identity(SavedSessionService.workspaceIdentity(workspace),root.threadId().value(),
                root.taskId().value(),root.initialRun().id().value(),root.generation().id().value(),
                CanonicalId.newId(EntityFamily.CONTEXT).value(),root.instructions().manifest().canonicalHash(),
                plan,null);
    }
    private static ContextInvocation invocation(Identity identity){return invocation(identity,OperationKind.CHAT);}
    private static ContextInvocation invocation(Identity identity,OperationKind operation){return new ContextInvocation(identity,
            operation,"semantic",null,List.of(),null,identity.planRevision(),identity.steeringRevision(),
            "mock","model",new CapabilityProfile("mock",30000,LimitProvenance.PROVIDER_REPORTED_LIMIT,
            128,32),false,DeterministicContextCompiler.COMPILER_VERSION,
            DeterministicContextCompiler.POLICY_VERSION);}
    private static void assertKinds(ContextSourceSet set,List<String> kinds){for(String kind:kinds)
        assertTrue(set.sources().stream().anyMatch(source->kind.equals(source.kind())),kind);}
}
