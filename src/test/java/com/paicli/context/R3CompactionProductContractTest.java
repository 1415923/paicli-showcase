package com.paicli.context;

import com.paicli.config.Config;
import com.paicli.context.ContextModels.*;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.command.InteractiveCompactionCommandService;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolCall;
import com.paicli.state.DatabaseManager;
import com.paicli.state.ContextGenerationStore;
import com.paicli.state.StateStore;
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

class R3CompactionProductContractTest {
    @TempDir Path workspace;

    @Test void AUTO_COMPACT_TRIGGER_PASS()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"auto-trigger"));
            var pointer=pointer(state,admitted,OperationKind.CHAT);
            var outcome=new CompactionCoordinator(state).autoCompact(pointer,admitted.guard());
            assertTrue(outcome.afterEstimatedTokens()<outcome.beforeEstimatedTokens());
            assertNotEquals(base.contextGenerationId(),pointer(state,admitted,OperationKind.CHAT)
                    .contextGenerationId());
            assertTrue(outcome.safeSummary().startsWith("Context compacted"));
            assertTrue(outcome.safeSummary().matches(
                    "Context compacted · before=\\d+ after=\\d+ generation=.{1,8}"));
        }
    }

    @Test void AUTO_COMPACT_NO_TRIGGER_BELOW_THRESHOLD()throws Exception{
        var budget=new Budget(689,10,50,50,1_000,LimitProvenance.CONFIGURED_LIMIT,
                EstimateProvenance.DERIVED_ESTIMATE,Budget.Decision.READY);
        try(StateStore state=new StateStore(workspace)){
            assertFalse(new CompactionCoordinator(state).shouldAutoCompact(budget));
            assertEquals(80,CompactionCoordinator.SOFT_TRIGGER_PERCENT);
            var atThreshold=new Budget(690,10,50,50,1_000,
                    LimitProvenance.CONFIGURED_LIMIT,
                    EstimateProvenance.DERIVED_ESTIMATE,Budget.Decision.READY);
            assertTrue(new CompactionCoordinator(state).shouldAutoCompact(atThreshold));
        }
    }

    @Test void AUTO_COMPACT_SINGLE_ATTEMPT()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"single-attempt"));
            var observed=pointer(state,admitted,OperationKind.CHAT);
            new CompactionCoordinator(state).autoCompact(observed,admitted.guard());
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->new CompactionCoordinator(state).autoCompact(observed,admitted.guard()));
            assertEquals("COMPACTION_STALE_SOURCE",failure.getMessage());
        }
    }

    @Test void AUTO_COMPACT_NO_EFFECTIVE_REDUCTION_FAIL_CLOSED()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,1,"no-reduction"));
            var observed=pointer(state,admitted,OperationKind.CHAT);
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->new CompactionCoordinator(state).autoCompact(observed,admitted.guard()));
            assertEquals("COMPACTION_NO_EFFECTIVE_REDUCTION",failure.getMessage());
            assertEquals(base.contextGenerationId(),pointer(state,admitted,OperationKind.CHAT)
                    .contextGenerationId());
        }
    }

    @Test void AUTO_COMPACT_STALE_CAS_REJECTED()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"stale-1"));
            var stale=pointer(state,admitted,OperationKind.CHAT);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"stale-2"));
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->new CompactionCoordinator(state).autoCompact(stale,admitted.guard()));
            assertEquals("COMPACTION_STALE_SOURCE",failure.getMessage());
        }
    }

    @Test void AUTO_COMPACT_DURABLE_SOURCE_PRESERVED()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"source-preserved"));
            int segmentCount=state.contexts.segments(base.contextGenerationId()).size();
            new CompactionCoordinator(state).autoCompact(pointer(state,admitted,OperationKind.CHAT),
                    admitted.guard());
            assertEquals(segmentCount,state.contexts.segments(base.contextGenerationId()).size());
            assertEquals(Lifecycle.SUPERSEDED,
                    state.contexts.find(base.contextGenerationId()).orElseThrow().lifecycle());
        }
    }

    @Test void AUTO_COMPACT_NO_DUPLICATE_TOOL()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            CanonicalId occurrence=admitted.guard().admitOccurrence(1,
                    new ToolCall("read_file",Map.of("path","README.md")));
            admitted.guard().completeOccurrence(occurrence,ToolResult.success("ok"));
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"no-duplicate"));
            long before=count("side_effect_occurrences");
            new CompactionCoordinator(state).autoCompact(pointer(state,admitted,OperationKind.CHAT),
                    admitted.guard());
            assertEquals(before,count("side_effect_occurrences"));
        }
    }

    @Test void AUTO_COMPACT_12K_MULTI_TURN_REACHABLE_WITHOUT_DUPLICATE_TOOL()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            for(int index=1;index<=9;index++){
                CanonicalId occurrence=admitted.guard().admitOccurrence(index,
                        new ToolCall("read_file",Map.of("path","fixture-"+index+".txt")));
                admitted.guard().completeOccurrence(occurrence,ToolResult.success("observed-"+index));
            }
            long occurrences=count("side_effect_occurrences");
            var base=state.contexts.publishBase(twelveThousandCompiled(admitted));
            assertEquals(12_000,base.budget().contextLimit());
            assertTrue(new CompactionCoordinator(state).shouldAutoCompact(base.budget()));
            var outcome=new CompactionCoordinator(state).autoCompact(
                    pointer(state,admitted,OperationKind.CHAT),admitted.guard());
            assertTrue(outcome.afterEstimatedTokens()<outcome.beforeEstimatedTokens());
            assertEquals(occurrences,count("side_effect_occurrences"));
            String ledger=state.contexts.verifiedActiveLedger(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow()
                    .segments().stream().map(value->value.segment().sanitizedPayload())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(ledger.contains("COMPLETED_TOOL_PAIR"));
            assertTrue(ledger.contains("DURABLE_RUNTIME_PROMPT"));
            assertFalse(ledger.contains("tiny-plan-step"));
            var active=pointer(state,admitted,OperationKind.CHAT);
            var activeSegments=state.contexts.segments(active.contextGenerationId());
            var verified=state.contexts.verifiedActiveLedger(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().segments();
            assertEquals(2,verified.size(),"each positive safe group must keep its own ledger envelope");
            int plan=kindIndex(activeSegments,"ACTIVE_PLAN");
            List<Integer> ledgers=java.util.stream.IntStream.range(0,activeSegments.size())
                    .filter(index->"COMPACTED_LEDGER".equals(activeSegments.get(index).kind()))
                    .boxed().toList();
            assertTrue(ledgers.getFirst()<plan&&plan<ledgers.getLast(),
                    "protected Plan gap must remain between the two projected groups");
            var projectedSources=new java.util.HashSet<String>();
            var rawReferences=state.contexts.references(active.contextGenerationId());
            for(var item:verified){
                long entries=item.segment().sanitizedPayload().lines().skip(1)
                        .filter(line->!line.isBlank()).count();
                var anchors=rawReferences.stream().filter(reference->
                        reference.ordinal()==item.segment().ordinal()
                                &&"SOURCE_SEGMENT".equals(reference.type())).toList();
                assertEquals(entries,anchors.size());
                for(var anchor:anchors)assertTrue(projectedSources.add(anchor.id()),
                        "one source may belong to only one ledger");
            }
            assertEquals(actualCandidateInput(activeSegments),
                    state.contexts.budget(active.contextGenerationId()).inputEstimate(),
                    "stored candidate input must equal the canonical Provider-message estimate");
        }
    }

    @Test void AUTO_COMPACT_NEGATIVE_GROUPS_CANNOT_BORROW_SHARED_ENVELOPE()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(smallIndependentGroupsCompiled(admitted));
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                            DeterministicContextCompiler.COMPILER_VERSION,
                            DeterministicContextCompiler.POLICY_VERSION,
                            admitted.guard().current().snapshot()));
            assertEquals("COMPACTION_NO_EFFECTIVE_REDUCTION",failure.getMessage());
        }
    }

    @Test void AUTO_COMPACT_NO_AUTHORITY_EXPANSION()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"authority"));
            var observed=pointer(state,admitted,OperationKind.CHAT);
            admitted.guard().admitOperationOccurrence("2","pending-provider","PROVIDER","CHAT",
                    "a".repeat(64));
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->new CompactionCoordinator(state).autoCompact(observed,admitted.guard()));
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",failure.getMessage());
            assertEquals(observed,pointer(state,admitted,OperationKind.CHAT));
        }
    }

    @Test void MANUAL_COMPACT_ACTIVE_RUN_REJECTED()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"manual-active"));
            InteractiveSession session=session("active");
            session.startTask(admitted.root().taskId().value(),admitted.root().initialRun().id().value());
            assertEquals("COMPACTION_ACTIVE_RUN_REJECTED",
                    new InteractiveCompactionCommandService(workspace)
                            .compact(List.of(),session.snapshot()).code());
        }
    }

    @Test void MANUAL_COMPACT_READY_PASS()throws Exception{
        ManualFixture fixture=manualFixture("manual-ready",true);
        java.util.concurrent.atomic.AtomicReference<CompactionCoordinator.Outcome> event=
                new java.util.concurrent.atomic.AtomicReference<>();
        var result=new InteractiveCompactionCommandService(workspace,()->null,
                (authority,outcome)->event.set(outcome))
                .compact(List.of(),fixture.session().snapshot());
        assertEquals("COMPACTION_READY",result.code());
        assertNotNull(event.get());
        assertTrue(event.get().safeSummary().startsWith("Context compacted"));
        try(StateStore state=new StateStore(workspace)){
            assertNotEquals(fixture.chat(),state.contexts.pointer(fixture.task(),fixture.run(),
                    OperationKind.CHAT).orElseThrow().contextGenerationId());
            assertNotEquals(fixture.repair(),state.contexts.pointer(fixture.task(),fixture.run(),
                    OperationKind.REPAIR).orElseThrow().contextGenerationId());
        }
    }

    @Test void MANUAL_COMPACT_SKIPS_NON_REDUCING_POINTER_WHEN_ANOTHER_POINTER_REDUCES()
            throws Exception{
        ManualFixture fixture=manualFixture("manual-mixed",true,1,9);
        var result=new InteractiveCompactionCommandService(workspace)
                .compact(List.of(),fixture.session().snapshot());
        assertEquals("COMPACTION_READY",result.code());
        try(StateStore state=new StateStore(workspace)){
            assertEquals(fixture.chat(),state.contexts.pointer(fixture.task(),fixture.run(),
                    OperationKind.CHAT).orElseThrow().contextGenerationId());
            assertNotEquals(fixture.repair(),state.contexts.pointer(fixture.task(),fixture.run(),
                    OperationKind.REPAIR).orElseThrow().contextGenerationId());
        }
    }

    @Test void MANUAL_COMPACT_AMBIGUOUS_FAIL_CLOSED()throws Exception{
        ManualFixture fixture=manualFixture("manual-binding",false);
        var result=new InteractiveCompactionCommandService(workspace)
                .compact(List.of(),fixture.session().snapshot());
        assertEquals("COMPACTION_NOT_APPLICABLE",result.code());
        try(StateStore state=new StateStore(workspace)){
            assertEquals(fixture.chat(),state.contexts.pointer(fixture.task(),fixture.run(),
                    OperationKind.CHAT).orElseThrow().contextGenerationId());
        }
    }

    @Test void COMPACT_PRIVATE_METADATA_NON_DISCLOSURE_PASS()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace,"owner-private",
                    "lease-private-DO-NOT-RENDER");
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"safe"));
            var outcome=new CompactionCoordinator(state).autoCompact(
                    pointer(state,admitted,OperationKind.CHAT),admitted.guard());
            String rendered=outcome.safeSummary();
            assertFalse(rendered.contains("lease-private"));
            assertFalse(rendered.contains("reasoning_content"));
            assertFalse(rendered.contains("credential"));
        }
    }

    private ManualFixture manualFixture(String marker,boolean matchingHead)throws Exception{
        return manualFixture(marker,matchingHead,9,9);
    }

    private ManualFixture manualFixture(String marker,boolean matchingHead,int chatTurns,
            int repairTurns)throws Exception{
        String task,run,thread,chat,repair;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            task=admitted.root().taskId().value();run=admitted.root().initialRun().id().value();
            thread=admitted.root().threadId().value();
            chat=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,chatTurns,marker+"-chat")).contextGenerationId();
            repair=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.REPAIR,repairTurns,marker+"-repair")).contextGenerationId();
        }
        terminal(task,run);
        InteractiveSession session=session(marker);
        String last=matchingHead?task:CanonicalId.newId(EntityFamily.TASK).value();
        session.startTask(last,CanonicalId.newId(EntityFamily.RUN).value());session.finishTask();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_TERMINAL,
                CanonicalId.parse(thread),CanonicalId.parse(task),"COMPLETED",Instant.now()));
        return new ManualFixture(session,thread,task,run,chat,repair);
    }

    private CompiledContext twelveThousandCompiled(R3ContextTestFixtures.Admitted admitted){
        var root=admitted.root();var authority=admitted.guard().current();
        Identity identity=new Identity(SavedSessionService.workspaceIdentity(workspace),
                root.threadId().value(),root.taskId().value(),root.initialRun().id().value(),
                root.generation().id().value(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                root.instructions().manifest().canonicalHash(),null,null);
        String intent=identity.contextGenerationId()+":intent";
        var capability=new CapabilityProfile("mock",12_000,
                LimitProvenance.CONFIGURED_LIMIT,256,128);
        ContextInvocation invocation=new ContextInvocation(identity,OperationKind.CHAT,"multi-turn",
                intent,List.of(),null,null,null,"mock","model",capability,false,
                DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        var sources=new ArrayList<ContextSource>();
        String ownership=identity.taskId()+"|"+identity.runId()+"|"
                +authority.fencingToken()+"|ACTIVE";
        String ownershipHash=DeterministicContextCompiler.hash(ownership);
        sources.add(new ContextSource(30,1,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "OWNERSHIP_FENCE",identity.taskId(),Long.toString(authority.fencingToken()),
                "R1_OWNERSHIP_FENCE",Scope.RUN_SCOPED,Compactability.NON_COMPACTABLE,
                PayloadMode.REFERENCE_RENDERED,DeterministicContextCompiler.RENDERER_VERSION,
                ownership,ownershipHash,PrivacyClass.INTERNAL,
                DeterministicContextCompiler.MAX_SEGMENT_BYTES,false,null,null,null,
                List.of(new SourceReference("OWNERSHIP_LEASE",identity.taskId(),
                        Long.toString(authority.fencingToken()),ownershipHash,0))));
        sources.add(R3ContextTestFixtures.source(10,0,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "RUNTIME_CONTRACT",identity.contextGenerationId()+":runtime",
                Compactability.NON_COMPACTABLE,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,"runtime contract"));
        sources.add(R3ContextTestFixtures.source(90,0,SourceClass.FRESH_USER_INTENT,
                "DURABLE_USER_INTENT",intent,Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                "Complete the long read task; never modify files"));
        sources.add(R3ContextTestFixtures.source(75,0,SourceClass.COMPACTABLE_DURABLE_HISTORY,
                "PLAN_STEP","tiny-plan-step",Compactability.COMPACTABLE,
                AgentMessage.Role.ASSISTANT,AgentMessage.MessageType.FINAL,
                "kind=PLAN_STEP;state=COMPLETED"));
        sources.add(R3ContextTestFixtures.source(79,0,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "ACTIVE_PLAN","protected-gap",Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                "Protected current Plan boundary"));
        for(int index=1;index<=9;index++)sources.add(R3ContextTestFixtures.source(78,index,
                SourceClass.COMPACTABLE_DURABLE_HISTORY,"COMPLETED_TOOL_PAIR","pair-"+index,
                Compactability.COMPACTABLE,AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                "kind=COMPLETED_TOOL_PAIR;tool_name=read_file;arguments_summary={path=fixture-"
                        +index+".txt};status=COMPLETED;result_excerpt="+"x".repeat(500)
                        +";occurrence_id=occ-"+index+";digest="+"d".repeat(64)));
        for(int index=1;index<=12;index++)sources.add(R3ContextTestFixtures.source(80,index,
                SourceClass.COMPACTABLE_DURABLE_HISTORY,"DURABLE_RUNTIME_PROMPT","prompt-"+index,
                Compactability.COMPACTABLE,AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                "runtime-step="+index+";"+"r".repeat(1_900)));
        List<ContextSource> ordered=sources.stream().sorted(ContextSourceSet.ORDER).toList();
        return new DeterministicContextCompiler().compile(new CompileRequest(invocation,
                new ContextSourceSet(ordered,DeterministicContextCompiler.sourceFingerprint(ordered)),
                List.of()));
    }

    private CompiledContext smallIndependentGroupsCompiled(R3ContextTestFixtures.Admitted admitted){
        var root=admitted.root();var authority=admitted.guard().current();
        Identity identity=new Identity(SavedSessionService.workspaceIdentity(workspace),
                root.threadId().value(),root.taskId().value(),root.initialRun().id().value(),
                root.generation().id().value(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                root.instructions().manifest().canonicalHash(),null,null);
        String intent=identity.contextGenerationId()+":intent";
        var capability=new CapabilityProfile("mock",30_000,
                LimitProvenance.CONFIGURED_LIMIT,128,32);
        ContextInvocation invocation=new ContextInvocation(identity,OperationKind.CHAT,"negative-groups",
                intent,List.of(),null,null,null,"mock","model",capability,false,
                DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        var sources=new ArrayList<ContextSource>();
        String ownership=identity.taskId()+"|"+identity.runId()+"|"
                +authority.fencingToken()+"|ACTIVE";
        String ownershipHash=DeterministicContextCompiler.hash(ownership);
        sources.add(new ContextSource(30,1,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "OWNERSHIP_FENCE",identity.taskId(),Long.toString(authority.fencingToken()),
                "R1_OWNERSHIP_FENCE",Scope.RUN_SCOPED,Compactability.NON_COMPACTABLE,
                PayloadMode.REFERENCE_RENDERED,DeterministicContextCompiler.RENDERER_VERSION,
                ownership,ownershipHash,PrivacyClass.INTERNAL,
                DeterministicContextCompiler.MAX_SEGMENT_BYTES,false,null,null,null,
                List.of(new SourceReference("OWNERSHIP_LEASE",identity.taskId(),
                        Long.toString(authority.fencingToken()),ownershipHash,0))));
        sources.add(R3ContextTestFixtures.source(10,0,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "RUNTIME_CONTRACT",identity.contextGenerationId()+":runtime",
                Compactability.NON_COMPACTABLE,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,"runtime"));
        for(int index=1;index<=5;index++)sources.add(R3ContextTestFixtures.source(78,index,
                SourceClass.COMPACTABLE_DURABLE_HISTORY,"COMPLETED_TOOL_PAIR","pair-"+index,
                Compactability.COMPACTABLE,AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                "kind=COMPLETED_TOOL_PAIR"));
        sources.add(R3ContextTestFixtures.source(79,0,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "ACTIVE_PLAN","negative-gap",Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,"current plan"));
        for(int index=1;index<=5;index++)sources.add(R3ContextTestFixtures.source(80,index,
                SourceClass.COMPACTABLE_DURABLE_HISTORY,"DURABLE_RUNTIME_PROMPT","prompt-"+index,
                Compactability.COMPACTABLE,AgentMessage.Role.USER,AgentMessage.MessageType.USER,"r"));
        sources.add(R3ContextTestFixtures.source(90,0,SourceClass.FRESH_USER_INTENT,
                "DURABLE_USER_INTENT",intent,Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,"goal"));
        List<ContextSource> ordered=sources.stream().sorted(ContextSourceSet.ORDER).toList();
        return new DeterministicContextCompiler().compile(new CompileRequest(invocation,
                new ContextSourceSet(ordered,DeterministicContextCompiler.sourceFingerprint(ordered)),
                List.of()));
    }

    private static int kindIndex(List<ContextGenerationStore.StoredSegment> segments,String kind){
        return java.util.stream.IntStream.range(0,segments.size())
                .filter(index->kind.equals(segments.get(index).kind())).findFirst().orElseThrow();
    }

    private static int actualCandidateInput(List<ContextGenerationStore.StoredSegment> segments){
        var estimator=new ContextBudgetResolver();
        int input=ContextBudgetResolver.PROTOCOL_OVERHEAD_TOKENS;
        for(var segment:segments){
            if("OWNERSHIP_FENCE".equals(segment.kind())||"TOOL_SCHEMA".equals(segment.kind()))continue;
            AgentMessage.Role role=Set.of("PLAN_STEP","ATTEMPT","EVIDENCE","TOOL_OCCURRENCE",
                    "TOOL_RESULT","ARTIFACT_REFERENCE","VERIFICATION_RESULT","REPAIR_ITERATION",
                    "DURABLE_ASSISTANT_RESULT","EVENT_FINAL").contains(segment.kind())
                    ?AgentMessage.Role.ASSISTANT:AgentMessage.Role.USER;
            AgentMessage.MessageType type=role==AgentMessage.Role.ASSISTANT
                    ?AgentMessage.MessageType.FINAL:AgentMessage.MessageType.USER;
            input=Math.addExact(input,estimator.estimate(role+"\n"+type+"\n"
                    +segment.sanitizedPayload()));
        }
        return input;
    }

    private void terminal(String task,String run)throws Exception{
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"));
            var taskUpdate=database.getConnection().prepareStatement(
                    "UPDATE task_identities SET status='COMPLETED',terminal_at=? WHERE task_id=?");
            var runUpdate=database.getConnection().prepareStatement(
                    "UPDATE durable_runs SET terminal_status='COMPLETED' WHERE task_id=? AND run_id=?")){
            taskUpdate.setString(1,Instant.now().toString());taskUpdate.setString(2,task);
            assertEquals(1,taskUpdate.executeUpdate());
            runUpdate.setString(1,task);runUpdate.setString(2,run);
            assertEquals(1,runUpdate.executeUpdate());
            try(var expired=database.getConnection().prepareStatement(
                    "UPDATE task_ownership_leases SET expires_at=? WHERE task_id=?")){
                expired.setString(1,Instant.EPOCH.toString());expired.setString(2,task);
                assertEquals(1,expired.executeUpdate());
            }
        }
    }

    private InteractiveSession session(String suffix){
        var session=new InteractiveSession("session-"+suffix,workspace,"mock","model",
                Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,Instant.now());
        session.start();return session;
    }

    private static ContextGenerationStore.Pointer pointer(StateStore state,
            R3ContextTestFixtures.Admitted admitted,OperationKind operation)throws Exception{
        return state.contexts.pointer(admitted.root().taskId().value(),
                admitted.root().initialRun().id().value(),operation).orElseThrow();
    }

    private long count(String table)throws Exception{
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=database.getConnection().createStatement();
            var row=statement.executeQuery("SELECT count(*) FROM "+table)){
            return row.next()?row.getLong(1):-1;
        }
    }

    private record ManualFixture(InteractiveSession session,String thread,String task,String run,
            String chat,String repair){}
}
