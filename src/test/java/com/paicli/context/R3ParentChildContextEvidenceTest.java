package com.paicli.context;

import com.paicli.config.Config;
import com.paicli.context.ContextModels.*;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.*;
import com.paicli.state.DatabaseManager;
import com.paicli.state.FollowUpModels.ChildStartStatus;
import com.paicli.state.ContextGenerationStore;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class R3ParentChildContextEvidenceTest {
    @TempDir Path workspace;

    @Test void realCapsuleChildBindingAndReopenedStoreIsolateParentAuthority()throws Exception{
        Instant at=Instant.now();
        CanonicalId childTask,childRun,parentTask,parentRun;
        String parentContext,parentPlanMarker="PARENT_PLAN_PRIVATE_MARKER";
        String parentManifestId;
        com.paicli.state.FollowUpModels.ChildStartClaim childClaim;
        Files.writeString(workspace.resolve("AGENTS.md"),"fresh child manifest rule");
        try(StateStore state=new StateStore(workspace)){
            var parent=new InstructionManifestRuntime(state,workspace).admitRoot(
                    CanonicalId.newId(EntityFamily.TASK),"parent goal",10_000,config(),
                    RuntimeManifest.disabled(),at);
            parentTask=parent.taskId();parentRun=parent.initialRun().id();
            parentManifestId=parent.instructions().manifest().id();
            assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(parentTask,
                    "parent-owner",parentRun,"parent-lease",at,at.plus(Duration.ofHours(1))));
            FencedExecutionGuard parentGuard=new FencedExecutionGuard(state,
                    TaskExecutionAuthority.initial(state.r1aDurable.readOwnership(parentTask).orElseThrow()),
                    Duration.ofSeconds(30),()->at.plusSeconds(1));
            AgentMessage parentUser=AgentMessage.userGoal("PARENT_RAW_TRANSCRIPT_PRIVATE");
            AgentMessage parentAssistant=AgentMessage.finalAnswer("PARENT_ASSISTANT_PRIVATE");
            parentGuard.appendEvent(0,parentUser);parentGuard.appendEvent(1,parentAssistant);
            List<AgentMessage> parentMessages=new ArrayList<>(List.of(parentUser,parentAssistant));
            parentGuard.recordPlan(CanonicalId.newId(EntityFamily.PLAN).value(),parentPlanMarker);
            parentGuard.recordApproval(2,"parent-call","run_command","HIGH","ALLOW",
                    "PARENT_APPROVAL_PRIVATE");
            CanonicalId parentTool=parentGuard.admitOccurrence(3,
                    new ToolCall("read_file",Map.of("path","PARENT_TOOL_ARGUMENT_PRIVATE")));
            parentGuard.recordToolCall(3,"parent-tool","read_file","{redacted}",
                    ToolResult.success("PARENT_TOOL_RESULT_PRIVATE"));
            parentGuard.completeOccurrence(parentTool,ToolResult.success("PARENT_TOOL_RESULT_PRIVATE"));
            AtomicReference<List<AgentMessage>> parentSent=new AtomicReference<>();
            ModelProvider parentDelegate=(messages,tools)->{
                parentSent.set(List.copyOf(messages));return ModelResponse.finalAnswer("parent-ok");};
            ContextualModelProvider parentProvider=provider(parentDelegate,state,parentTask.value(),false);
            ModelResponse parentResponse=parentProvider.chat(OperationKind.CHAT,"parent",
                    List.of(parentUser,parentAssistant),List.of(),parentGuard);
            parentGuard.completeOccurrence(CanonicalId.parse(parentResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,new R3ContextTestFixtures.Admitted(parent,parentGuard),
                    OperationKind.CHAT,9,"PARENT_INHERITED_LEDGER"));
            var basePointer=state.contexts.pointer(parentTask.value(),parentRun.value(),
                    OperationKind.CHAT).orElseThrow();
            var compacted=new CompactionCoordinator(state).autoCompact(basePointer,parentGuard);
            assertTrue(compacted.afterEstimatedTokens()<compacted.beforeEstimatedTokens());
            parentContext=state.contexts.pointer(parentTask.value(),parentRun.value(),OperationKind.CHAT)
                    .orElseThrow().contextGenerationId();
            assertNotEquals(basePointer.contextGenerationId(),parentContext);
            assertFalse(parentSent.get().isEmpty());
            appendCompletedPair(parentGuard,parentMessages,11,"parent-ledger-1",
                    "ledger-1.txt","PARENT_INHERITED_LEDGER FIRST_MARKER "+"x".repeat(900));
            appendCompletedPair(parentGuard,parentMessages,12,"parent-ledger-2",
                    "ledger-2.txt","PARENT_OLD_TOOL_2 "+"x".repeat(900));
            appendCompletedPair(parentGuard,parentMessages,13,"parent-middle-3",
                    "middle-3.txt","PARENT_OLD_TOOL_3 "+"x".repeat(900));
            appendCompletedPair(parentGuard,parentMessages,14,"parent-middle-4",
                    "middle-4.txt","PARENT_OLD_TOOL_4 "+"x".repeat(900));
            appendCompletedPair(parentGuard,parentMessages,15,"parent-middle-5",
                    "middle-5.txt","PARENT_OLD_TOOL_5 "+"x".repeat(900));
            appendCompletedPair(parentGuard,parentMessages,16,"parent-retained-6",
                    "retained-6.txt","PARENT_RETAINED_PAIR_MARKER MIDDLE_MARKER PROHIBITION_MARKER");
            appendCompletedPair(parentGuard,parentMessages,17,"parent-latest-7",
                    "latest-7.txt","PARENT_LATEST_PAIR_MARKER LAST_MARKER UNFINISHED_GOAL_MARKER\n"
                            +"[PAICLI_TOOL_FACTS] PARENT_PRIVATE_CANONICAL_ENVELOPE");
            // Eligible short results still exercise real compaction. Canonical metadata
            // stays on the raw pair path; the child must still exclude its private envelope.
            ModelResponse parentToolResponse=parentProvider.chat(OperationKind.TOOL_CONTINUATION,
                    "parent-tool",List.copyOf(parentMessages),List.of(),parentGuard);
            parentGuard.completeOccurrence(CanonicalId.parse(
                    parentToolResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            ModelResponse parentChatResponse=parentProvider.chat(OperationKind.CHAT,"parent-chat",
                    List.copyOf(parentMessages),List.of(),parentGuard);
            parentGuard.completeOccurrence(CanonicalId.parse(
                    parentChatResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            var mixedBase=state.contexts.pointer(parentTask.value(),parentRun.value(),
                    OperationKind.TOOL_CONTINUATION).orElseThrow();
            var mixedCompacted=new CompactionCoordinator(state).autoCompact(mixedBase,parentGuard);
            assertTrue(mixedCompacted.afterEstimatedTokens()<mixedCompacted.beforeEstimatedTokens());
            parentContext=state.contexts.pointer(parentTask.value(),parentRun.value(),
                    OperationKind.TOOL_CONTINUATION)
                    .orElseThrow().contextGenerationId();
            assertNotEquals(mixedBase.contextGenerationId(),parentContext);

            terminal(parentTask,parentRun,at);
            FollowUpService followUps=new FollowUpService(state,
                    new InstructionManifestRuntime(state,workspace),config(),RuntimeManifest.disabled(),10_000);
            var outcome=followUps.begin(parent.threadId(),"continue child safely",true,
                    "interactive:r3","r3-session",at.plusSeconds(2));
            assertNotNull(outcome.child());childTask=outcome.child().taskId();childRun=outcome.child().runId();
            var claimed=state.followUps.claimInitialChildStart(outcome.child(),"child-owner","child-lease",
                    at.plusSeconds(3),at.plus(Duration.ofHours(1)),Duration.ZERO);
            assertEquals(ChildStartStatus.CLAIMED,claimed.status());childClaim=claimed.claim();
            assertTrue(state.followUps.markInitialChildStarted(childClaim,at.plusSeconds(4)));
        }

        AtomicReference<List<AgentMessage>> childSent=new AtomicReference<>();
        try(StateStore reopened=new StateStore(workspace)){
            FencedExecutionGuard childGuard=new FencedExecutionGuard(reopened,
                    TaskExecutionAuthority.initial(childClaim.lease()),Duration.ofSeconds(30),
                    ()->at.plusSeconds(5));
            AgentMessage childGoal=AgentMessage.userGoal("CHILD_DURABLE_INTENT");
            childGuard.appendEvent(0,childGoal);
            ModelProvider childDelegate=new ModelProvider(){
                @Override public boolean usesManagedAnswerEnvelope(){return true;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    return chatForOperation(operation,messages,tools);
                }
                @Override public ModelResponse chat(List<AgentMessage> messages,List<com.paicli.model.ToolDefinition> tools){
                    childSent.set(List.copyOf(messages));return ModelResponse.finalAnswer("child-ok");}
            };
            ModelResponse childResponse=provider(childDelegate,reopened,childTask.value(),true).chat(
                    OperationKind.CHAT,"child-initial",List.of(childGoal),List.of(),childGuard);
            assertEquals("child-ok",childResponse.content());
            assertTrue(childSent.get().stream().noneMatch(m->m.content()!=null
                    &&m.content().contains(ManagedAnswerContract.VERSION)));
            childGuard.completeOccurrence(CanonicalId.parse(childResponse.providerOccurrenceId()),
                    ToolResult.success("PROVIDER_RESPONSE_FINAL"));
            String childContext=reopened.contexts.pointer(childTask.value(),childRun.value(),
                    OperationKind.CHAT).orElseThrow().contextGenerationId();
            var generation=reopened.contexts.find(childContext).orElseThrow();
            assertEquals(childRun.value(),generation.runId());
            String childManifestId=reopened.r1aDurable.findManifestByTask(childTask).orElseThrow().id();
            assertNotEquals(parentManifestId,childManifestId,
                    "Follow-up admission creates a fresh Child Manifest identity");
            String visible=childSent.get().stream().map(AgentMessage::content)
                    .collect(Collectors.joining("\n"));
            String activeParentContext=parentContext;
            assertTrue(visible.contains("Continuation Capsule:"));
            assertTrue(visible.contains(CompactedLedgerCodec.CHILD_COMPLETED_TOOL_VERSION),
                    "COMPACT_CONTINUATION_INHERITANCE_PASS");
            assertFalse(visible.contains(CompactedLedgerCodec.VERSION));
            assertEquals(1,count(visible,"PARENT_INHERITED_LEDGER"));
            assertEquals(1,count(visible,"PARENT_RETAINED_PAIR_MARKER"));
            assertEquals(1,count(visible,"PARENT_LATEST_PAIR_MARKER"));
            for(String marker:List.of("FIRST_MARKER","MIDDLE_MARKER","LAST_MARKER",
                    "PROHIBITION_MARKER","UNFINISHED_GOAL_MARKER"))
                assertEquals(1,count(visible,marker),marker);
            assertFalse(visible.contains("Frozen Instruction Manifest:"));
            var childAudit=reopened.contexts.segments(childContext);
            String manifestHash=reopened.contexts.find(childContext).orElseThrow().manifestHash();
            assertTrue(childAudit.stream().anyMatch(segment->"FROZEN_MANIFEST".equals(segment.kind())
                    &&segment.contentHash().equals(DeterministicContextCompiler.hash(
                            "Frozen Instruction Manifest: "+manifestHash))));
            assertTrue(childAudit.stream().anyMatch(segment->"IDENTITY".equals(segment.kind())
                    &&segment.sanitizedPayload().contains(childTask.value())
                    &&segment.sanitizedPayload().contains(childRun.value())));
            assertTrue(visible.contains("CHILD_DURABLE_INTENT"));
            assertFalse(visible.contains("PARENT_RAW_TRANSCRIPT_PRIVATE"));
            assertFalse(visible.contains("PARENT_ASSISTANT_PRIVATE"));
            assertFalse(visible.contains(parentPlanMarker));
            assertFalse(visible.contains("PARENT_APPROVAL_PRIVATE"));
            assertFalse(visible.contains("PARENT_TOOL_ARGUMENT_PRIVATE"));
            assertFalse(visible.contains("PARENT_TOOL_RESULT_PRIVATE"));
            assertFalse(visible.contains("PARENT_PRIVATE_CANONICAL_ENVELOPE"));
            assertFalse(visible.contains("PARENT_AUTHORITY_PRIVATE"));
            assertFalse(visible.contains(parentTask.value()));
            assertFalse(visible.contains(parentRun.value()));
            assertFalse(visible.contains(activeParentContext));
            List<ContextGenerationStore.StoredReference> childReferences=
                    reopened.contexts.references(childContext);
            assertTrue(childReferences.stream().anyMatch(reference->
                    "PARENT_CONTEXT_SEGMENT".equals(reference.type())));
            assertTrue(childReferences.stream().filter(reference->
                            "PARENT_CONTEXT_SEGMENT".equals(reference.type()))
                    .allMatch(reference->reference.id().startsWith(activeParentContext+":")));
            assertNotEquals(reopened.contexts.find(activeParentContext).orElseThrow().manifestHash(),
                    "", "the immutable Parent Context remains independently readable");

            String childGeneration=childClaim.child().generationId().value();
            ContextGenerationStore.ParentHistoryTarget staleTarget=reopened.contexts
                    .captureParentHistoryTarget(childTask.value(),childRun.value(),childGeneration)
                    .orElseThrow();
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    Statement statement=manager.getConnection().createStatement()){
                assertEquals(1,statement.executeUpdate("""
                  UPDATE context_generation_pointers
                  SET pointer_version=pointer_version+1
                  WHERE task_id='%s' AND run_id='%s' AND operation_kind='%s'
                  """.formatted(parentTask.value(),parentRun.value(),
                          staleTarget.pointer().operation().name())));
            }
            var stale=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->reopened.contexts.verifiedParentHistory(staleTarget));
            assertEquals("COMPACTION_STALE_SOURCE",stale.getMessage());

            ContextGenerationStore.ParentHistoryTarget corruptTarget=reopened.contexts
                    .captureParentHistoryTarget(childTask.value(),childRun.value(),childGeneration)
                    .orElseThrow();
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    Statement statement=manager.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_segment_update_immutable");
                assertTrue(statement.executeUpdate("""
                  UPDATE context_segments SET sanitized_payload=sanitized_payload||' tampered'
                  WHERE context_generation_id='%s' AND segment_kind='COMPACTED_LEDGER'
                  """.formatted(activeParentContext))>0);
            }
            var corrupt=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->reopened.contexts.verifiedParentHistory(corruptTarget));
            assertEquals("COMPACTION_INTEGRITY",corrupt.getMessage());
        }
    }

    private void appendCompletedPair(FencedExecutionGuard guard,List<AgentMessage> messages,
            int iteration,String callId,String path,String output)throws Exception{
        String tool=output.contains("[PAICLI_TOOL_FACTS] ")?"list_files":"read_file";
        String arguments="""
                {"path":"%s"}
                """.formatted(path).trim();
        String toolJson="""
                {"id":"%s","type":"function","function":
                 {"name":"%s","arguments":%s}}
                """.formatted(callId,tool,arguments).trim();
        AgentMessage action=AgentMessage.actionWithToolCall("read "+path,callId,toolJson);
        AgentMessage observation=AgentMessage.observationForToolCall(output,callId);
        guard.appendEvent(iteration,action);
        CanonicalId occurrence=guard.admitOccurrence(iteration,callId,
                new ToolCall(tool,Map.of("path",path),callId));
        guard.recordToolCall(iteration,callId,tool,arguments,ToolResult.success(output));
        guard.completeOccurrence(occurrence,ToolResult.success(output));
        guard.appendEvent(iteration,observation);
        messages.add(action);messages.add(observation);
    }

    private static int count(String value,String marker){
        int count=0,offset=0;
        while((offset=value.indexOf(marker,offset))>=0){count++;offset+=marker.length();}
        return count;
    }

    private ContextualModelProvider provider(ModelProvider delegate,StateStore state,String taskId,
            boolean child){
        return new ContextualModelProvider(delegate,state,workspace,taskId,"mock","model",
                OperationKind.CHAT,new CapabilityProfile("mock",131072,
                LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),child,"runtime");
    }
    private Config config(){
        return new Config(workspace.toString(),30,50_000,200_000,Config.MODE_WORKSPACE_WRITE,
                10_000,Config.PROVIDER_MOCK,"model","","","system",10,"",120,50_000,1,
                false,false,false);
    }
    private void terminal(CanonicalId task,CanonicalId run,Instant at)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                Statement statement=manager.getConnection().createStatement()){
            statement.executeUpdate("UPDATE task_identities SET status='COMPLETED',terminal_at='"
                    +at+"' WHERE task_id='"+task.value()+"'");
            statement.executeUpdate("UPDATE durable_runs SET terminal_status='COMPLETED' WHERE run_id='"
                    +run.value()+"'");
            statement.executeUpdate("UPDATE tasks SET status='DONE',completed_at='"+at+"' WHERE task_id='"
                    +task.value()+"'");
            statement.executeUpdate("UPDATE coding_runs SET phase='COMPLETED',completed_at='"+at
                    +"' WHERE run_id='"+run.value()+"'");
        }
    }
}
