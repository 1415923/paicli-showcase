package com.paicli.state;

import com.paicli.context.ContextModels.*;
import com.paicli.context.CompactionCoordinator;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.agent.ToolFactProjection;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolCall;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class R3DurableContextSourceAssemblerTest {
    @TempDir Path workspace;
    @Test void allObservedBatchMembersHideOnlyRedundantExecutionMetadata()throws Exception{
        try(var state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var user=AgentMessage.userGoal("inspect two files");
            var action=AgentMessage.actionWithToolCall("read two files","call-a",
                    "[{\"id\":\"call-a\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}},"
                    +"{\"id\":\"call-b\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{}\"}}]");
            var messages=new ArrayList<AgentMessage>(List.of(user,action));
            admitted.guard().appendEvent(0,user);admitted.guard().appendEvent(1,action);
            for(String callId:List.of("call-a","call-b")){
                var result=ToolResult.success("contents "+callId);
                var occurrence=admitted.guard().admitOccurrence(1,callId.equals("call-a")?1:2,callId,
                        new ToolCall("read_file",Map.of("path",callId+".txt"),callId));
                admitted.guard().recordToolCall(1,callId,"read_file","{}",result);
                admitted.guard().completeOccurrence(occurrence,result);
                var observation=AgentMessage.observationForToolCall(result.output(),callId);
                admitted.guard().appendEvent(1,observation);messages.add(observation);
            }
            Identity identity=R3ContextTestFixtures.compiled(workspace,admitted,OperationKind.CHAT,0,"batch").identity();
            var invocation=new ContextInvocation(identity,OperationKind.TOOL_CONTINUATION,"1",null,List.of(),null,null,null,
                    "mock","model",new CapabilityProfile("mock",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                    false,DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION);
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                    invocation,"runtime",messages,List.of(),admitted.guard().current()));
            var facts=assembled.sources().sources().stream().filter(source->
                    Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind())).toList();
            assertEquals(4,facts.size());
            assertTrue(facts.stream().noneMatch(ContextSource::providerVisible));
            var compiled=new DeterministicContextCompiler().compile(new CompileRequest(
                    assembled.invocation(),assembled.sources(),assembled.tools()));
            assertEquals(List.of("call-a","call-b"),compiled.messages().stream()
                    .filter(m->m.type()==AgentMessage.MessageType.OBSERVATION).map(AgentMessage::toolCallId).toList());
            assertEquals(AgentMessage.MessageType.OBSERVATION,compiled.messages().getLast().type());
            assertTrue(compiled.messages().stream().noneMatch(m->m.type()==AgentMessage.MessageType.FINAL));
        }
    }

    @Test void oldObservationWithReusedCallIdCannotHideNewUnobservedExecution()throws Exception{
        try(var state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var user=AgentMessage.userGoal("continue the inspection");
            var oldAction=AgentMessage.actionWithToolCall("old rejected call","reused-call","{private}");
            var oldObservation=AgentMessage.observationForToolCall("NOT_EXECUTED: batch rejected","reused-call");
            admitted.guard().appendEvent(0,user);
            admitted.guard().appendEvent(1,oldAction);admitted.guard().appendEvent(1,oldObservation);
            var occurrence=admitted.guard().admitOccurrence(2,new ToolCall("read_file",Map.of("path","new.txt")));
            admitted.guard().recordToolCall(2,"reused-call","read_file","{}",ToolResult.success("new result"));
            admitted.guard().completeOccurrence(occurrence,ToolResult.success("new result"));
            Identity identity=R3ContextTestFixtures.compiled(workspace,admitted,OperationKind.CHAT,0,"reused").identity();
            for(var operation:List.of(OperationKind.CHAT,OperationKind.PLAN)){
                var invocation=new ContextInvocation(identity,operation,"2",null,List.of(),null,null,null,
                        "mock","model",new CapabilityProfile("mock",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                        false,DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION);
                var messages=operation==OperationKind.PLAN?List.of(user):List.of(user,oldAction,oldObservation);
                var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                        invocation,"runtime",messages,List.of(),admitted.guard().current()));
                var facts=assembled.sources().sources().stream().filter(source->
                        Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind())
                                &&source.stableSourceId().equals(occurrence.value())).toList();
                assertEquals(2,facts.size());assertTrue(facts.stream().allMatch(ContextSource::providerVisible),
                        "an older rejected call or PLAN input is not evidence that the new result was observed");
                assertTrue(facts.stream().allMatch(source->source.role()==AgentMessage.Role.USER
                        &&source.messageType()!=AgentMessage.MessageType.FINAL),
                        "orphan runtime facts remain evidence, never assistant answers");
            }
        }
    }

    @Test void toolContinuationUsesExplicitOperationAndStableDurableSourcesAcrossProcesses()throws Exception{
        Identity identity;ContextInvocation invocation;List<AgentMessage> messages;
        com.paicli.runtime.TaskExecutionAuthority authority;List<String> firstClosure;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            AgentMessage user=AgentMessage.userGoal("durable intent");
            AgentMessage action=AgentMessage.actionWithToolCall("tool request","call-1","{private}");
            ToolResult listResult=ToolFactProjection.bind(ToolResult.success(
                    "README.md\ndocs/\nnotes.txt\n",Map.of(
                            ToolFactProjection.VISIBLE_ENTRY_COUNT,3,
                            ToolFactProjection.EXCLUDED_CONTROL_COUNT,1,
                            ToolFactProjection.EXCLUDED_CONTROLS,".paicli/",
                            ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,true)),"call-1",
                    "list_files");
            String durableObservation=ToolFactProjection.appendCanonicalFacts(
                    listResult.output(),listResult);
            AgentMessage observation=AgentMessage.observationForToolCall(
                    durableObservation,"call-1");
            admitted.guard().appendEvent(0,user);admitted.guard().appendEvent(1,action);
            CanonicalId occurrence=admitted.guard().admitOccurrence(1,
                    new ToolCall("list_files",Map.of("path",".")));
            admitted.guard().recordToolCall(1,"call-1","list_files","Authorization: secret",
                    ToolResult.success(durableObservation));
            admitted.guard().completeOccurrence(occurrence,listResult);
            admitted.guard().appendEvent(1,observation);messages=List.of(user,action,observation);
            identity=new Identity(SavedSessionService.workspaceIdentity(workspace),root.threadId().value(),
                    root.taskId().value(),root.initialRun().id().value(),root.generation().id().value(),
                    CanonicalId.newId(EntityFamily.CONTEXT).value(),
                    root.instructions().manifest().canonicalHash(),null,null);
            invocation=new ContextInvocation(identity,OperationKind.TOOL_CONTINUATION,"1",null,List.of(),
                    null,null,null,"mock","model",new CapabilityProfile("mock",131072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            authority=admitted.guard().current();
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                    invocation,"system",messages,List.of(),authority));
            assertEquals(OperationKind.TOOL_CONTINUATION,assembled.invocation().operation());
            assertNotNull(assembled.invocation().currentUserIntentSourceId());
            assertEquals(1,assembled.invocation().toolResultSourceIds().size());
            assertTrue(assembled.sources().sources().stream().flatMap(source->source.references().stream())
                    .noneMatch(reference->reference.type().startsWith("MESSAGE_")));
            assertTrue(assembled.sources().sources().stream().anyMatch(source->
                    "call-1".equals(source.toolCallId())&&source.kind().equals("EVENT_OBSERVATION")));
            assertTrue(assembled.sources().sources().stream().anyMatch(source->
                    source.kind().equals("TOOL_OCCURRENCE")&&source.stableSourceId().equals(occurrence.value())));
            assertTrue(assembled.sources().sources().stream().anyMatch(source->
                    source.kind().equals("TOOL_RESULT")&&source.renderedPayload().contains("tool_name=list_files")
                            &&!source.renderedPayload().contains("state.db")));
            CompiledContext compiled=new DeterministicContextCompiler().compile(new CompileRequest(
                    assembled.invocation(),assembled.sources(),assembled.tools()));
            assertEquals("system",compiled.messages().getFirst().content());
            assertEquals(user,compiled.messages().get(1),"task goal precedes changing execution history");
            assertTrue(assembled.sources().sources().stream().filter(source->
                    Set.of("IDENTITY","FROZEN_MANIFEST","TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind()))
                    .allMatch(source->!source.providerVisible()
                            &&source.compactability()==Compactability.NON_COMPACTABLE));
            assertFalse(compiled.messages().stream().anyMatch(message->
                    message.content().contains(identity.contextGenerationId())));
            Identity nextIdentity=new Identity(identity.workspaceId(),identity.threadId(),identity.taskId(),
                    identity.runId(),identity.authorityGenerationId(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                    identity.manifestHash(),identity.planRevision(),identity.steeringRevision());
            var nextInvocation=new ContextInvocation(nextIdentity,invocation.operation(),"2",null,List.of(),
                    null,null,null,"mock","model",invocation.capability(),false,
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION);
            var next=state.readOnlySnapshot(()->state.contextSources.assemble(
                    nextInvocation,"system",messages,List.of(),authority));
            var nextCompiled=new DeterministicContextCompiler().compile(new CompileRequest(
                    next.invocation(),next.sources(),next.tools()));
            assertEquals(compiled.messages(),nextCompiled.messages(),"new context identity does not invalidate model prefix");
            assertNotEquals(compiled.compiledHash(),nextCompiled.compiledHash(),"identity remains in audit hash");
            assertNotEquals(compiled.sourceFingerprint(),nextCompiled.sourceFingerprint());
            var hidden=assembled.sources().sources().stream().filter(source->source.kind().equals("IDENTITY"))
                    .findFirst().orElseThrow();
            var tampered=new ContextSource(hidden.priority(),hidden.sequence(),hidden.sourceClass(),hidden.kind(),
                    hidden.stableSourceId(),hidden.revision(),hidden.authorityKind(),hidden.scope(),
                    hidden.compactability(),hidden.payloadMode(),hidden.rendererVersion(),"tampered identity",
                    hidden.contentHash(),hidden.privacyClass(),hidden.bounds(),false,hidden.role(),hidden.messageType(),
                    hidden.toolCallId(),hidden.toolCallJson(),hidden.references());
            var badSources=new ArrayList<>(assembled.sources().sources());
            badSources.set(badSources.indexOf(hidden),tampered);
            assertThrows(IllegalArgumentException.class,()->new DeterministicContextCompiler().compile(
                    new CompileRequest(assembled.invocation(),new ContextSourceSet(badSources,
                            DeterministicContextCompiler.sourceFingerprint(badSources)),assembled.tools())));
            AgentMessage compiledAction=compiled.messages().stream()
                    .filter(message->message.type()==AgentMessage.MessageType.ACTION)
                    .findFirst().orElseThrow();
            assertEquals("call-1",compiledAction.toolCallId());
            assertEquals("{private}",compiledAction.toolCallJson());
            AgentMessage compiledObservation=compiled.messages().stream()
                    .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION)
                    .findFirst().orElseThrow();
            assertEquals("call-1",compiledObservation.toolCallId());
            assertEquals(durableObservation,compiledObservation.content());
            assertNull(compiledObservation.toolCallJson(),
                    "OBSERVATION must not reuse ACTION-only toolCallJson for canonical facts");
            var canonicalFact=ToolFactProjection.fromObservation(
                    compiledObservation.content(),compiledObservation.toolCallId());
            assertNotNull(canonicalFact);
            assertEquals("RUNTIME_VERIFIED",canonicalFact.trustClass());
            assertTrue(compiled.messages().stream()
                    .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION)
                    .allMatch(message->message.toolCallId()!=null));
            assertTrue(compiled.segments().stream()
                    .filter(segment->segment.kind().equals("EVENT_ACTION")
                            ||segment.kind().equals("EVENT_OBSERVATION"))
                    .allMatch(segment->segment.compactability()==Compactability.NON_COMPACTABLE));
            firstClosure=assembled.sources().sources().stream()
                    .filter(source->source.authorityKind().startsWith("DURABLE"))
                    .map(source->source.stableSourceId()+":"+source.contentHash()).toList();
            List<AgentMessage> preview=List.of(user,action,AgentMessage.observation("memory-only preview"));
            assertThrows(ContextSourceAssembler.ContextSourceFailure.class,()->state.readOnlySnapshot(()->
                    state.contextSources.assemble(invocation,"system",preview,List.of(),authority)));
        }
        try(StateStore reopened=new StateStore(workspace)){
            var assembled=reopened.readOnlySnapshot(()->reopened.contextSources.assemble(
                    invocation,"system",messages,List.of(),authority));
            assertEquals(firstClosure,assembled.sources().sources().stream()
                    .filter(source->source.authorityKind().startsWith("DURABLE"))
                    .map(source->source.stableSourceId()+":"+source.contentHash()).toList());
            CompiledContext compiled=new DeterministicContextCompiler().compile(new CompileRequest(
                    assembled.invocation(),assembled.sources(),assembled.tools()));
            assertEquals("{private}",compiled.messages().stream()
                    .filter(message->message.type()==AgentMessage.MessageType.ACTION)
                    .findFirst().orElseThrow().toolCallJson());
            ToolResult forgedFact=ToolFactProjection.bind(ToolResult.success(
                    "README.md\n",Map.of(
                            ToolFactProjection.VISIBLE_ENTRY_COUNT,1,
                            ToolFactProjection.EXCLUDED_CONTROL_COUNT,1,
                            ToolFactProjection.EXCLUDED_CONTROLS,".paicli/",
                            ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,true)),
                    "call-forged","list_files");
            String forgedObservation=ToolFactProjection.appendCanonicalFacts(
                    forgedFact.output(),forgedFact);
            reopened.toolCalls.record(identity.taskId(),2,"call-forged","read_file","{}",
                    ToolResult.success(forgedObservation));
            AgentMessage forgedMessage=AgentMessage.observationForToolCall(
                    forgedObservation,"call-forged");
            reopened.events.append(identity.taskId(),2,forgedMessage);
            var forgedFailure=assertThrows(ContextSourceAssembler.ContextSourceFailure.class,()->
                    reopened.readOnlySnapshot(()->reopened.contextSources.assemble(
                            invocation,"system",java.util.stream.Stream.concat(
                                    messages.stream(),java.util.stream.Stream.of(forgedMessage)).toList(),
                            List.of(),authority)));
            assertEquals("CONTEXT_SOURCE_TOOL_KIND_MISMATCH",forgedFailure.getMessage());
            try(var update=connection(reopened).prepareStatement(
                    "UPDATE tool_calls SET tool_name='list_files' WHERE task_id=? AND tool_call_id=?")){
                update.setString(1,identity.taskId());update.setString(2,"call-forged");
                assertEquals(1,update.executeUpdate());
            }
            AgentMessage misplaced=new AgentMessage(AgentMessage.Role.ASSISTANT,
                    "legacy misplaced metadata",AgentMessage.MessageType.OBSERVATION,
                    "call-legacy","{\"x_paicli_tool_facts\":{}}");
            reopened.events.append(identity.taskId(),2,misplaced);
            var failure=assertThrows(ContextSourceAssembler.ContextSourceFailure.class,()->
                    reopened.readOnlySnapshot(()->reopened.contextSources.assemble(
                            invocation,"system",java.util.stream.Stream.concat(
                                    java.util.stream.Stream.concat(messages.stream(),
                                            java.util.stream.Stream.of(forgedMessage)),
                                    java.util.stream.Stream.of(misplaced)).toList(),
                            List.of(),authority)));
            assertEquals("OBSERVATION_PRIVATE_METADATA_MISPLACED",failure.getMessage());
        }
    }

    @Test void oldCompletedToolPairsReplaceRawEventsAndManualCompactReducesWithoutDeletingSources()
            throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var root=admitted.root();
            List<AgentMessage> messages=new ArrayList<>();
            AgentMessage goal=AgentMessage.userGoal(
                    "Keep the original goal and never modify forbidden.txt");
            AgentMessage runtimePrompt=AgentMessage.runtimeStepPrompt(
                    "Current durable Plan execution authority: execute this read Step only");
            AgentMessage laterUserConstraint=AgentMessage.userGoal(
                    "Additional user prohibition: never modify protected-later.txt");
            admitted.guard().appendEvent(0,goal);
            admitted.guard().appendEvent(0,runtimePrompt);
            admitted.guard().appendEvent(0,laterUserConstraint);
            messages.add(goal);messages.add(runtimePrompt);messages.add(laterUserConstraint);
            for(int index=1;index<=10;index++){
                String callId="call-real-pair-"+index;
                String arguments="{\"path\":\"fixture-"+index+".txt\"}";
                AgentMessage action=AgentMessage.actionWithToolCall(
                        "Read fixture "+index,callId,"{\"id\":\""+callId
                                +"\",\"type\":\"function\",\"function\":{\"name\":\"read_file\","
                                +"\"arguments\":"+arguments+"}}");
                // This compaction scenario needs eligible, complete projections. Long result
                // retention is covered separately by ToolContextRetentionTest.
                String output="marker-"+index+"\n"+"x".repeat(900);
                AgentMessage observation=AgentMessage.observationForToolCall(output,callId);
                admitted.guard().appendEvent(index,action);
                CanonicalId occurrence=admitted.guard().admitOccurrence(index,callId,
                        new ToolCall("read_file",Map.of("path","fixture-"+index+".txt"),callId));
                admitted.guard().recordToolCall(index,callId,"read_file",arguments,
                        ToolResult.success(output));
                admitted.guard().completeOccurrence(occurrence,ToolResult.success(output));
                admitted.guard().appendEvent(index,observation);
                messages.add(action);messages.add(observation);
            }
            Identity identity=new Identity(SavedSessionService.workspaceIdentity(workspace),
                    root.threadId().value(),root.taskId().value(),root.initialRun().id().value(),
                    root.generation().id().value(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                    root.instructions().manifest().canonicalHash(),null,null);
            ContextInvocation invocation=new ContextInvocation(identity,OperationKind.CHAT,"real-pairs",
                    null,List.of(),null,null,null,"mock","model",
                    new CapabilityProfile("mock",10_000,
                            LimitProvenance.PROVIDER_REPORTED_LIMIT,256,128),false,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            var assembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                    invocation,"system",messages,List.of(),admitted.guard().current()));
            List<ContextSource> sources=assembled.sources().sources();
            assertEquals(9,sources.stream().filter(source->
                    "COMPLETED_TOOL_PAIR".equals(source.kind())).count());
            assertEquals(1,sources.stream().filter(source->
                    "EVENT_ACTION".equals(source.kind())).count());
            assertEquals(1,sources.stream().filter(source->
                    "EVENT_OBSERVATION".equals(source.kind())).count());
            List<ContextSource> latestToolFacts=sources.stream().filter(source->
                    Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind())).toList();
            assertEquals(2,latestToolFacts.size());
            assertTrue(latestToolFacts.stream().allMatch(source->
                    source.compactability()==Compactability.NON_COMPACTABLE));
            assertTrue(sources.stream().filter(source->
                            "COMPLETED_TOOL_PAIR".equals(source.kind()))
                    .allMatch(source->source.providerVisible()
                            &&source.compactability()==Compactability.COMPACTABLE
                            &&source.role()==AgentMessage.Role.USER
                            &&source.references().stream().filter(reference->
                                    "EVENT".equals(reference.type())).count()==2
                            &&source.references().stream().anyMatch(reference->
                                    "TOOL_OCCURRENCE".equals(reference.type()))
                            &&source.references().stream().anyMatch(reference->
                                    "TOOL_RESULT_AUTHORITY".equals(reference.type()))));
            List<ContextSource> intents=sources.stream().filter(source->
                    "DURABLE_USER_INTENT".equals(source.kind())).toList();
            assertEquals(2,intents.size());
            assertTrue(intents.stream().allMatch(source->
                    source.compactability()==Compactability.NON_COMPACTABLE));
            assertTrue(intents.stream().anyMatch(source->source.renderedPayload()
                    .contains("never modify forbidden.txt")));
            assertTrue(intents.stream().anyMatch(source->source.renderedPayload()
                    .contains("never modify protected-later.txt")));
            List<ContextSource> runtimePrompts=sources.stream().filter(source->
                    "DURABLE_RUNTIME_PROMPT".equals(source.kind())).toList();
            assertEquals(1,runtimePrompts.size());
            assertEquals(Compactability.COMPACTABLE,runtimePrompts.getFirst().compactability());
            assertEquals(AgentMessage.MessageType.USER,runtimePrompts.getFirst().messageType());

            ContextSource tinyStep=R3ContextTestFixtures.source(75,0,
                    SourceClass.COMPACTABLE_DURABLE_HISTORY,"PLAN_STEP","tiny-plan-step",
                    Compactability.COMPACTABLE,AgentMessage.Role.USER,
                    AgentMessage.MessageType.USER,"kind=PLAN_STEP;state=COMPLETED");
            var augmentedSources=new ArrayList<>(assembled.sources().sources());
            augmentedSources.add(tinyStep);
            List<ContextSource> augmentedOrdered=augmentedSources.stream()
                    .sorted(ContextSourceSet.ORDER).toList();
            CompiledContext compiled=new DeterministicContextCompiler().compile(
                    new CompileRequest(assembled.invocation(),new ContextSourceSet(augmentedOrdered,
                    DeterministicContextCompiler.sourceFingerprint(augmentedOrdered)),assembled.tools()));
            assertEquals(2,compiled.segments().stream().filter(segment->
                    Set.of("EVENT_ACTION","EVENT_OBSERVATION").contains(segment.kind())
                            &&segment.compactability()==Compactability.NON_COMPACTABLE).count());
            assertEquals(9,compiled.messages().stream().filter(message->
                    message.content().startsWith("kind=COMPLETED_TOOL_PAIR")).count());
            long durableEvents=count(connection(state),"events");
            long durableCalls=count(connection(state),"tool_calls");
            long durableOccurrences=count(connection(state),"side_effect_occurrences");
            var base=state.contexts.publishBase(compiled);
            Identity toolIdentity=new Identity(identity.workspaceId(),identity.threadId(),identity.taskId(),
                    identity.runId(),identity.authorityGenerationId(),
                    CanonicalId.newId(EntityFamily.CONTEXT).value(),identity.manifestHash(),
                    identity.planRevision(),identity.steeringRevision());
            ContextInvocation toolInvocation=new ContextInvocation(toolIdentity,
                    OperationKind.TOOL_CONTINUATION,"real-pairs-tool",
                    assembled.invocation().currentUserIntentSourceId(),
                    assembled.invocation().toolResultSourceIds(),null,null,null,"mock","model",
                    invocation.capability(),false,DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            var toolAssembled=state.readOnlySnapshot(()->state.contextSources.assemble(
                    toolInvocation,"system",messages,List.of(),admitted.guard().current()));
            var toolSources=new ArrayList<>(toolAssembled.sources().sources());toolSources.add(tinyStep);
            List<ContextSource> toolOrdered=toolSources.stream().sorted(ContextSourceSet.ORDER).toList();
            var toolBase=state.contexts.publishBase(new DeterministicContextCompiler().compile(
                    new CompileRequest(toolAssembled.invocation(),new ContextSourceSet(toolOrdered,
                    DeterministicContextCompiler.sourceFingerprint(toolOrdered)),toolAssembled.tools())));
            terminalize(connection(state),identity.taskId(),identity.runId());
            var authority=state.contexts.captureMaintenanceAuthority("session-real-pairs",
                    identity.threadId(),identity.taskId(),null);
            var outcome=new CompactionCoordinator(state).manualCompact(authority);
            assertTrue(outcome.afterEstimatedTokens()<outcome.beforeEstimatedTokens());
            assertNotEquals(base.contextGenerationId(),state.contexts.pointer(identity.taskId(),
                    identity.runId(),OperationKind.CHAT).orElseThrow().contextGenerationId());
            assertNotEquals(toolBase.contextGenerationId(),state.contexts.pointer(identity.taskId(),
                    identity.runId(),OperationKind.TOOL_CONTINUATION).orElseThrow()
                    .contextGenerationId());
            Set<String> compactedLeaves=state.contexts.verifiedActiveLedger(identity.taskId(),
                            identity.runId(),OperationKind.CHAT).orElseThrow().segments().stream()
                    .flatMap(ledger->ledger.references().stream())
                    .map(reference->reference.type()+":"+reference.id()).collect(
                            java.util.stream.Collectors.toSet());
            assertTrue(compactedLeaves.stream().anyMatch(value->value.startsWith("EVENT:")));
            assertTrue(compactedLeaves.stream().anyMatch(value->value.startsWith("TOOL_OCCURRENCE:")));
            assertTrue(compactedLeaves.stream().anyMatch(value->
                    value.startsWith("TOOL_RESULT_AUTHORITY:")));
            String activeLedger=state.contexts.verifiedActiveLedger(identity.taskId(),identity.runId(),
                    OperationKind.CHAT).orElseThrow().segments().getFirst().segment()
                    .sanitizedPayload();
            assertTrue(activeLedger.contains("COMPLETED_TOOL_PAIR"));
            assertFalse(activeLedger.contains("tiny-plan-step"),
                    "a net-expanding tiny semantic entry must not block or ride the Tool-pair group");

            ContextInvocation inherited=nextInvocation(invocation,"after-compact");
            var afterCandidate=state.readOnlySnapshot(()->state.contextSources.assemble(
                    inherited,"system",messages,List.of(),admitted.guard().current()));
            Set<String> candidateProjection=toolHistoryProjection(afterCandidate.sources());
            CompiledContext inheritedCompiled=new DeterministicContextCompiler().compile(
                    new CompileRequest(afterCandidate.invocation(),afterCandidate.sources(),
                            afterCandidate.tools()));
            state.contexts.publishBase(inheritedCompiled);
            Set<String> baseLeaves=state.contexts.verifiedActiveLedger(identity.taskId(),
                            identity.runId(),OperationKind.CHAT).orElseThrow().segments().stream()
                    .flatMap(ledger->ledger.references().stream())
                    .map(reference->reference.type()+":"+reference.id()).collect(
                            java.util.stream.Collectors.toSet());
            assertEquals(compactedLeaves,baseLeaves,
                    "BASE-over-COMPACTED must retain recursive durable suppression provenance");
            ContextInvocation secondTurn=nextInvocation(invocation,"second-turn");
            var afterBase=state.readOnlySnapshot(()->state.contextSources.assemble(
                    secondTurn,"system",messages,List.of(),admitted.guard().current()));
            assertEquals(candidateProjection,toolHistoryProjection(afterBase.sources()),
                    "the second turn must not reintroduce compacted EVENT or semantic Tool sources");
            assertEquals(durableEvents,count(connection(state),"events"));
            assertEquals(durableCalls,count(connection(state),"tool_calls"));
            assertEquals(durableOccurrences,count(connection(state),"side_effect_occurrences"));
        }
    }

    private static ContextInvocation nextInvocation(ContextInvocation source,String sequence){
        Identity current=source.identity();
        Identity next=new Identity(current.workspaceId(),current.threadId(),current.taskId(),
                current.runId(),current.authorityGenerationId(),
                CanonicalId.newId(EntityFamily.CONTEXT).value(),current.manifestHash(),
                current.planRevision(),current.steeringRevision());
        return new ContextInvocation(next,source.operation(),sequence,null,List.of(),null,
                source.expectedPlanRevision(),source.expectedSteeringRevision(),source.provider(),
                source.model(),source.capability(),source.childContext(),source.compilerVersion(),
                source.policyVersion());
    }

    private static Set<String> toolHistoryProjection(ContextSourceSet sourceSet){
        return sourceSet.sources().stream().filter(source->Set.of("COMPLETED_TOOL_PAIR","EVENT_ACTION",
                        "EVENT_OBSERVATION","TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind()))
                .map(source->source.kind()+":"+source.stableSourceId())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void terminalize(java.sql.Connection connection,String task,String run)
            throws Exception{
        try(var taskUpdate=connection.prepareStatement(
                    "UPDATE task_identities SET status='COMPLETED',terminal_at=? WHERE task_id=?");
            var runUpdate=connection.prepareStatement(
                    "UPDATE durable_runs SET terminal_status='COMPLETED' WHERE task_id=? AND run_id=?")){
            taskUpdate.setString(1,Instant.now().toString());taskUpdate.setString(2,task);
            assertEquals(1,taskUpdate.executeUpdate());
            runUpdate.setString(1,task);runUpdate.setString(2,run);
            assertEquals(1,runUpdate.executeUpdate());
        }
    }

    private static long count(java.sql.Connection connection,String table)throws Exception{
        try(var statement=connection.createStatement();
            var row=statement.executeQuery("SELECT count(*) FROM "+table)){
            return row.next()?row.getLong(1):-1;
        }
    }

    private static java.sql.Connection connection(StateStore store)throws Exception{
        var field=StateStore.class.getDeclaredField("databaseManager");field.setAccessible(true);
        return ((DatabaseManager)field.get(store)).getConnection();
    }
}
