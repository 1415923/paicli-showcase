package com.paicli.state;

import com.paicli.context.ContextModels.*;
import com.paicli.context.CompactTargetModels.*;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.context.ContextualModelProvider;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.TaskExecutionAuthority;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class R3ContextGenerationStoreTest {
    @TempDir Path workspace;
    @Test void legacyV3BaseGenerationSurvivesReopenAndIsReplacedWithoutRewrite()throws Exception{
        String taskId;String runId;String legacyId;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            taskId=admitted.root().taskId().value();runId=admitted.root().initialRun().id().value();
            AgentMessage user=AgentMessage.userGoal("legacy context compatibility goal");
            admitted.guard().appendEvent(0,user);
            admitted.guard().saveCheckpoint(0,"RUNNING",List.of(user));
            CompiledContext current=R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,0,"legacy-v3");
            CompiledContext legacy=new CompiledContext(current.identity(),current.operation(),
                    current.provider(),current.model(),current.capability(),"R3_CONTEXT_COMPILER_V3",
                    current.policyVersion(),current.messages(),current.tools(),current.segments(),
                    current.sourceFingerprint(),current.compiledHash(),current.budget());
            legacyId=state.contexts.publishBase(legacy).contextGenerationId();
            assertEquals("R3_CONTEXT_COMPILER_V3",state.contexts.find(legacyId).orElseThrow().compilerVersion());
        }
        try(StateStore reopened=new StateStore(workspace)){
            assertEquals(legacyId,reopened.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
            assertEquals(0,new ContextRebuilder(reopened,workspace,4096,4096)
                    .rebuild(taskId).startIteration());
            var lease=reopened.r1aDurable.readOwnership(CanonicalId.parse(taskId)).orElseThrow();
            FencedExecutionGuard guard=FencedExecutionGuard.system(reopened,
                    TaskExecutionAuthority.initial(lease));
            var provider=new ContextualModelProvider((messages,tools)->ModelResponse.finalAnswer("ok"),
                    reopened,workspace,taskId,"mock","model",OperationKind.CHAT,
                    ContextualModelProvider.capability("mock"),false,"compatibility control");
            assertEquals(ModelResponse.ResponseType.FINAL,provider.chat(OperationKind.CHAT,"1",
                    reopened.events.findMessages(taskId),List.of(),guard).type());
            var replacement=reopened.contexts.pointer(taskId,runId,OperationKind.CHAT).orElseThrow();
            assertNotEquals(legacyId,replacement.contextGenerationId());
            assertEquals(DeterministicContextCompiler.COMPILER_VERSION,reopened.contexts
                    .find(replacement.contextGenerationId()).orElseThrow().compilerVersion());
            assertEquals(Lifecycle.SUPERSEDED,reopened.contexts.find(legacyId).orElseThrow().lifecycle());
            assertEquals("R3_CONTEXT_COMPILER_V3",reopened.contexts.find(legacyId)
                    .orElseThrow().compilerVersion(),"legacy generation remains immutable");
        }
    }
    @Test void basePreviewApplyAndRepeatedApplyAreAtomicAndIdempotent()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"goal"));
            assertEquals(Lifecycle.READY,base.lifecycle());
            assertThrows(java.sql.SQLException.class,()->update(
                    "UPDATE context_generations SET model='tampered' WHERE context_generation_id=?",
                    base.contextGenerationId()));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            assertEquals(Lifecycle.PREPARED,candidate.lifecycle());
            assertEquals(candidate.contextId(),state.contexts.createOrReuseCandidate(
                    base.contextGenerationId(),DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId());
            assertThrows(java.sql.SQLException.class,()->update(
                    "UPDATE context_segments SET sanitized_payload='tampered' WHERE context_generation_id=?",
                    candidate.contextId()));
            assertThrows(java.sql.SQLException.class,()->execute("""
                    INSERT INTO context_segments(context_generation_id,ordinal,source_class,segment_kind,
                      scope,compactability,payload_mode,sanitized_payload,content_hash,estimated_tokens,
                      privacy_class,bounds)
                    SELECT '%s',999,source_class,segment_kind,scope,compactability,payload_mode,
                      sanitized_payload,content_hash,estimated_tokens,privacy_class,bounds
                    FROM context_segments WHERE context_generation_id='%s' LIMIT 1
                    """.formatted(candidate.contextId(),candidate.contextId())));
            assertThrows(java.sql.SQLException.class,()->execute(
                    "DELETE FROM context_segments WHERE context_generation_id='"
                            +candidate.contextId()+"' AND ordinal=0"));
            assertThrows(java.sql.SQLException.class,()->update(
                    "DELETE FROM context_source_references WHERE context_generation_id=?",
                    candidate.contextId()));
            assertThrows(java.sql.SQLException.class,()->update(
                    "UPDATE context_source_references SET source_hash='"+"0".repeat(64)
                            +"' WHERE context_generation_id=?",candidate.contextId()));
            assertThrows(java.sql.SQLException.class,()->execute(
                    "INSERT INTO context_source_references(context_generation_id,ordinal,source_type,"
                    +"source_id,source_revision,source_hash,source_order) SELECT '"
                    +candidate.contextId()+"',0,'CONTEXT_INLINE','extra','1','"
                    +"0".repeat(64)+"',999"));
            assertEquals(base.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().contextGenerationId());
            var applied=state.contexts.applyCandidate(candidate.contextId(),
                    admitted.guard().current().snapshot());
            assertEquals(Lifecycle.READY,applied.lifecycle());
            assertEquals(candidate.contextId(),state.contexts.applyCandidate(candidate.contextId(),
                    admitted.guard().current().snapshot()).contextGenerationId());
            assertEquals(Lifecycle.SUPERSEDED,state.contexts.find(base.contextGenerationId()).orElseThrow().lifecycle());
        }
    }
    @Test void previewWithoutCompactableHistoryIsNotApplicable()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,0,"fresh"));
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                            DeterministicContextCompiler.COMPILER_VERSION,
                            DeterministicContextCompiler.POLICY_VERSION,
                            admitted.guard().current().snapshot()));
            assertEquals("COMPACTION_NOT_APPLICABLE",failure.getMessage());
        }
    }
    @Test void publishedCompactableSourceWithoutTypedRendererFailsPreviewClosed()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            CompiledContext original=R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"unsupported");
            var changed=new java.util.ArrayList<Segment>();boolean replaced=false;
            for(Segment segment:original.segments()){
                if(!replaced&&segment.compactability()==Compactability.COMPACTABLE){
                    changed.add(new Segment(segment.ordinal(),segment.sourceClass(),"UNSUPPORTED_TYPED_FACT",
                            segment.scope(),segment.compactability(),segment.payloadMode(),
                            segment.sanitizedPayload(),segment.contentHash(),segment.estimatedTokens(),
                            segment.privacyClass(),segment.bounds(),segment.references()));replaced=true;
                }else changed.add(segment);
            }
            CompiledContext unsupported=new CompiledContext(original.identity(),original.operation(),
                    original.provider(),original.model(),original.capability(),original.compilerVersion(),
                    original.policyVersion(),original.messages(),original.tools(),List.copyOf(changed),
                    DeterministicContextCompiler.sourceClosureFingerprint(changed),original.compiledHash(),
                    original.budget());
            String source=state.contexts.publishBase(unsupported).contextGenerationId();
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.createOrReuseCandidate(source,DeterministicContextCompiler.COMPILER_VERSION,
                            DeterministicContextCompiler.POLICY_VERSION,
                            admitted.guard().current().snapshot()));
            assertEquals("COMPACTION_UNSUPPORTED_SOURCE",failure.getMessage());
            assertEquals(Lifecycle.FAILED,state.contexts.candidateFor(source).orElseThrow().lifecycle());
            assertEquals(source,state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }
    @Test void structuredLedgerPreservesFactsUnresolvedItemsAndRecentHistory()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,9,"semantic"));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            var segments=state.contexts.segments(candidate.contextId());
            var ledger=segments.stream().filter(segment->"COMPACTED_LEDGER".equals(segment.kind()))
                    .map(ContextGenerationStore.StoredSegment::sanitizedPayload)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(ledger.contains("R3_COMPACTED_LEDGER_V4"));
            assertTrue(ledger.contains("\"entry_kind\":\"VERBATIM_REFERENCE\""));
            assertTrue(ledger.contains("\"entry_kind\":\"STRUCTURED_DERIVED\""));
            assertFalse(ledger.contains("\"entry_kind\":\"UNRESOLVED\""));
            assertTrue(segments.stream().filter(segment->!"COMPACTED_LEDGER".equals(segment.kind()))
                    .map(ContextGenerationStore.StoredSegment::sanitizedPayload)
                    .filter(java.util.Objects::nonNull).anyMatch(payload->payload.contains("fixture blocker")));
            assertTrue(ledger.contains("durable fact semantic"));
            assertTrue(ledger.contains("\"source_id\""));
            assertTrue(ledger.contains("\"source_revision\""));
            assertTrue(ledger.contains("\"bounded_provider_visible_payload\""));
            assertTrue(segments.stream().filter(segment->!"COMPACTED_LEDGER".equals(segment.kind()))
                    .filter(segment->segment.compactability()==Compactability.COMPACTABLE).count()>=4);
            assertEquals(Compactability.NON_COMPACTABLE,segments.stream()
                    .filter(segment->"COMPACTED_LEDGER".equals(segment.kind())).findFirst()
                    .orElseThrow().compactability());
        }
    }
    @Test void sourceDriftFailsCandidateAndLeavesNewReadyPointer()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var first=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"old"));
            var candidate=state.contexts.createOrReuseCandidate(first.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            var next=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"new"));
            assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.applyCandidate(candidate.contextId(),
                            admitted.guard().current().snapshot()));
            assertEquals(Lifecycle.FAILED,state.contexts.find(candidate.contextId()).orElseThrow().lifecycle());
            assertEquals(next.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().contextGenerationId());
        }
    }
    @Test void controlledPayloadDamageWithPreservedHashFailsAndKeepsOldReady()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"integrity"));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            try(var inspection=new DatabaseManager(workspace.resolve(".paicli").resolve("state"));
                var statement=inspection.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_segment_update_immutable");
                statement.executeUpdate("UPDATE context_segments SET sanitized_payload='tampered' "
                        +"WHERE context_generation_id='"+candidate.contextId()+"' "
                        +"AND payload_mode='INLINE_SANITIZED'");
            }
            var failure=assertThrows(ContextGenerationStore.ContextFailure.class,()->
                    state.contexts.applyCandidate(candidate.contextId(),
                            admitted.guard().current().snapshot()));
            assertEquals("COMPACTION_INTEGRITY",failure.getMessage());
            assertEquals(base.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().contextGenerationId());
            assertEquals(Lifecycle.FAILED,state.contexts.find(candidate.contextId()).orElseThrow().lifecycle());
        }
    }
    @Test void controlledReferenceInsertionFailsDeterministicCandidateRebuild()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"reference-integrity"));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            try(var inspection=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=inspection.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_reference_insert_immutable");
                statement.executeUpdate("INSERT INTO context_source_references VALUES('"
                        +candidate.contextId()+"',0,'CONTEXT_INLINE','extra-authority','1','"
                        +"0".repeat(64)+"',999)");
            }
            assertEquals("COMPACTION_INTEGRITY",assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.applyCandidate(candidate.contextId(),
                            admitted.guard().current().snapshot())).getMessage());
            assertEquals(base.contextGenerationId(),state.contexts.pointer(
                    admitted.root().taskId().value(),admitted.root().initialRun().id().value(),
                    OperationKind.CHAT).orElseThrow().contextGenerationId());
        }
    }
    @Test void compilerAndPolicyDriftFailPreparedCandidate()throws Exception{
        assertVersionDrift(workspace.resolve("compiler"),"compiler_version","OLD_COMPILER");
        assertVersionDrift(workspace.resolve("policy"),"policy_version","OLD_POLICY");
        assertSegmentDrift(workspace.resolve("privacy"),"privacy_class","'PUBLIC'");
        assertSegmentDrift(workspace.resolve("bounds"),"bounds","bounds+1");
    }
    @Test void twoIndependentStoresRaceOnePointerCasAndOneReadyReplacement()throws Exception{
        String candidateId,sourceId,taskId,runId;ExecutionAuthoritySnapshot authority;
        try(StateStore setup=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(setup,workspace);taskId=admitted.root().taskId().value();
            runId=admitted.root().initialRun().id().value();
            sourceId=setup.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"race")).contextGenerationId();
            candidateId=setup.contexts.createOrReuseCandidate(sourceId,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId();
            authority=admitted.guard().current().snapshot();
        }
        try(StateStore left=new StateStore(workspace);StateStore right=new StateStore(workspace);
            var executor=Executors.newFixedThreadPool(2)){
            CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
            var a=executor.submit(()->{ready.countDown();go.await();
                return left.contexts.applyCandidate(candidateId,authority);});
            var b=executor.submit(()->{ready.countDown();go.await();
                return right.contexts.applyCandidate(candidateId,authority);});
            ready.await();go.countDown();
            assertEquals(candidateId,a.get().contextGenerationId());
            assertEquals(candidateId,b.get().contextGenerationId());
            assertEquals(candidateId,left.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
            assertEquals(2,left.contexts.find(candidateId).orElseThrow().stateVersion());
            assertEquals(Lifecycle.SUPERSEDED,left.contexts.find(sourceId).orElseThrow().lifecycle());
            try(var inspection=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=inspection.getConnection().prepareStatement("SELECT count(*) FROM context_generations WHERE source_context_generation_id=? AND lifecycle='READY'")){
                statement.setString(1,sourceId);try(var row=statement.executeQuery()){
                    assertTrue(row.next());assertEquals(1,row.getInt(1));
                }
            }
        }
    }
    @Test void expiredPreparingCandidateIsTerminalizedBeforeDeterministicReclaim()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"crash"));
            String crashed=CanonicalId.newId(EntityFamily.CONTEXT).value();
            try(var inspection=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=inspection.getConnection().prepareStatement("""
                  INSERT INTO context_generations(context_generation_id,workspace_id,thread_id,task_id,
                    run_id,source_authority_generation_id,generation_kind,operation_kind,provider,model,
                    capability_profile,compiler_version,policy_version,manifest_hash,plan_revision,
                    steering_revision,source_fingerprint,limit_provenance,estimate_provenance,
                    source_context_generation_id,source_pointer_version,lifecycle,state_version,
                    created_at,updated_at)
                  SELECT ?,workspace_id,thread_id,task_id,run_id,source_authority_generation_id,
                    'COMPACTED',operation_kind,provider,model,capability_profile,compiler_version,
                    policy_version,manifest_hash,plan_revision,steering_revision,source_fingerprint,
                    limit_provenance,estimate_provenance,context_generation_id,1,'PREPARING',0,?,?
                  FROM context_generations WHERE context_generation_id=?
                  """)){
                statement.setString(1,crashed);statement.setString(2,"2020-01-01T00:00:00Z");
                statement.setString(3,"2020-01-01T00:00:00Z");statement.setString(4,base.contextGenerationId());
                assertEquals(1,statement.executeUpdate());
            }
            var reclaimed=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            assertNotEquals(crashed,reclaimed.contextId());assertEquals(Lifecycle.PREPARED,reclaimed.lifecycle());
            assertEquals(Lifecycle.FAILED,state.contexts.find(crashed).orElseThrow().lifecycle());
            assertEquals("COMPACTION_INTERRUPTED",state.contexts.find(crashed).orElseThrow().failureCode());
            assertEquals(base.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().contextGenerationId());
        }
    }
    @Test void damagedPointerIsIntegrityFailureAndNeverBecomesAUniqueFallback()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"valid-chat"));
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.REPAIR,8,"damaged-repair"));
            try(var damage=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=damage.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_pointer_ready_update");
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.executeUpdate("UPDATE context_generation_pointers SET "
                        +"active_context_generation_id='ctx_00000000000040008000000000000000' "
                        +"WHERE operation_kind='REPAIR'");
            }
            var resolution=state.contexts.resolveTarget(root.taskId().value(),root.initialRun().id().value(),
                    null,null,TargetRelation.EXPLICIT_TARGET);
            assertEquals(ResolutionStatus.INTEGRITY_FAILURE,resolution.status());
            assertNull(resolution.identity());assertTrue(resolution.candidates().isEmpty());
        }
    }
    private void assertVersionDrift(Path root,String column,String value)throws Exception{
        try(StateStore state=new StateStore(root)){
            var admitted=R3ContextTestFixtures.admit(state,root);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    root,admitted,OperationKind.CHAT,8,column));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            try(var inspection=new DatabaseManager(root.resolve(".paicli").resolve("state"));
                var statement=inspection.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_frozen_immutable");
                statement.executeUpdate("UPDATE context_generations SET "+column+"='"+value
                        +"' WHERE context_generation_id='"+candidate.contextId()+"'");
            }
            assertEquals("COMPACTION_INTEGRITY",assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.applyCandidate(candidate.contextId(),
                            admitted.guard().current().snapshot())).getMessage());
            assertEquals(base.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT).orElseThrow().contextGenerationId());
        }
    }
    private void update(String sql,String contextId)throws Exception{
        try(var inspection=new DatabaseManager(workspace.resolve(".paicli").resolve("state"));
            var statement=inspection.getConnection().prepareStatement(sql)){
            statement.setString(1,contextId);statement.executeUpdate();
        }
    }
    private void execute(String sql)throws Exception{
        try(var inspection=new DatabaseManager(workspace.resolve(".paicli").resolve("state"));
            var statement=inspection.getConnection().createStatement()){
            statement.executeUpdate(sql);
        }
    }
    private void assertSegmentDrift(Path root,String column,String expression)throws Exception{
        try(StateStore state=new StateStore(root)){
            var admitted=R3ContextTestFixtures.admit(state,root);
            var base=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    root,admitted,OperationKind.CHAT,8,column));
            var candidate=state.contexts.createOrReuseCandidate(base.contextGenerationId(),
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot());
            try(var inspection=new DatabaseManager(root.resolve(".paicli/state"));
                var statement=inspection.getConnection().createStatement()){
                statement.execute("DROP TRIGGER v26_context_segment_update_immutable");
                statement.executeUpdate("UPDATE context_segments SET "+column+"="+expression
                        +" WHERE context_generation_id='"+candidate.contextId()+"' AND ordinal=0");
            }
            assertEquals("COMPACTION_INTEGRITY",assertThrows(ContextGenerationStore.ContextFailure.class,
                    ()->state.contexts.applyCandidate(candidate.contextId(),
                            admitted.guard().current().snapshot())).getMessage());
            assertEquals(base.contextGenerationId(),state.contexts.pointer(admitted.root().taskId().value(),
                    admitted.root().initialRun().id().value(),OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }
}
