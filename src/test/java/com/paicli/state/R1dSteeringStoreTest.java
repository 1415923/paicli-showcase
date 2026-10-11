package com.paicli.state;

import com.paicli.identity.*;
import com.paicli.runtime.SteeringClassifier;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.R1aStoreResults.*;
import com.paicli.state.SteeringModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class R1dSteeringStoreTest {
    @TempDir Path tempDir;
    private static final Instant AT=Instant.parse("2026-07-19T00:00:00Z");

    @Test void unknownHasNoSequenceUntilCurrentInputOwnerConfirms()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("unknown"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            var admitted=store.steerings.admit(target,new SteeringClassifier().classify("Could this be nicer?"),
                    Provenance.INTERACTIVE_ACTIVE_TEXT,"interactive:s1","s1",AT);
            assertEquals(SteeringStore.Status.NEEDS_CONFIRMATION,admitted.status());
            assertEquals(State.NEEDS_CONFIRMATION,admitted.queue().state());assertNull(admitted.queue().sequence());
            assertEquals(1,scalar(store,"SELECT count(*) FROM steering_state_facts WHERE steering_id=? AND state='CANDIDATE'",admitted.steeringId().value()));
            assertEquals(SteeringStore.Status.OWNER_MISMATCH,store.steerings.confirm(admitted.steeringId(),
                    ConfirmationDecision.CONFIRM_NON_MATERIAL,"interactive:other","other",admitted.queue().version(),AT).status());
            var confirmed=store.steerings.confirm(admitted.steeringId(),
                    ConfirmationDecision.CONFIRM_NON_MATERIAL,"interactive:s1","s1",admitted.queue().version(),AT.plusSeconds(1));
            assertEquals(SteeringStore.Status.SUCCESS,confirmed.status());
            assertEquals(State.QUEUED,confirmed.queue().state());assertNotNull(confirmed.queue().sequence());
        }
    }

    @Test void queueIsOrderedAndNonMaterialApplicationIsExactlyOnce()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("apply"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            var first=store.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(1));
            var second=store.steerings.admit(target,new SteeringClassifier().classify("ordering: oldest first"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(2));
            assertTrue(first.queue().sequence()<second.queue().sequence());
            var applied=store.steerings.applyOldest(authority,AT.plusSeconds(3),"NEXT_STEP_ATTEMPT_BEFORE",false);
            assertEquals(SteeringStore.Status.SUCCESS,applied.status());
            assertEquals(first.steeringId(),applied.application().steeringId());
            assertEquals(State.APPLIED,store.steerings.readQueue(first.steeringId()).orElseThrow().state());
            assertEquals(1,scalar(store,"SELECT count(*) FROM steering_application_facts WHERE steering_id=?",first.steeringId().value()));
            assertEquals(1,scalar(store,"SELECT count(*) FROM steering_constraint_provenance WHERE steering_id=?",first.steeringId().value()));
            var next=store.steerings.applyOldest(authority,AT.plusSeconds(4),"NEXT_STEP_ATTEMPT_BEFORE",false);
            assertEquals(second.steeringId(),next.application().steeringId());
        }
    }

    @Test void unsafeOccurrenceAndStaleFenceCannotApply()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("unsafe"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            store.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(1));
            CanonicalId occurrence=CanonicalId.newId(EntityFamily.OCCURRENCE);
            assertEquals(FencedMutationResult.SUCCESS,store.fencedMutations.admitOccurrence(authority,AT.plusSeconds(2),
                    new SideEffectOccurrence(occurrence,seed.thread,seed.task,seed.run,"1","steering-test-op",null,
                            "TOOL","write","STARTED","a".repeat(64),null,null,AT.plusSeconds(2),null)));
            assertEquals(SteeringStore.Status.UNSAFE_POINT,store.steerings.applyOldest(authority,
                    AT.plusSeconds(3),"TOOL_OCCURRENCE_TERMINAL",false).status());
            var stale=new ExecutionAuthoritySnapshot(seed.task,seed.run,"owner","lease",99,authority.casVersion());
            assertEquals(SteeringStore.Status.FENCING_REJECTED,store.steerings.applyOldest(stale,
                    AT.plusSeconds(3),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
        }
    }

    @Test void definitionsAndTerminalFactsAreImmutable()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("immutable"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            var admitted=store.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT);
            assertThrows(SQLException.class,()->execute(store,"UPDATE steering_definitions SET content='changed' WHERE steering_id=?",admitted.steeringId().value()));
            assertEquals(SteeringStore.Status.SUCCESS,store.steerings.cancel(admitted.steeringId(),
                    "interactive:s1","s1",admitted.queue().version(),AT.plusSeconds(1)).status());
            assertThrows(SQLException.class,()->execute(store,"UPDATE steering_queue_authority SET state='QUEUED' WHERE steering_id=?",admitted.steeringId().value()));
        }
    }

    @Test void queueAndTaskTerminalizationHaveOneSerializedWinner()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("terminal-race-queue"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            store.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(1));
            assertEquals(FencedMutationResult.INVALID_STATE,store.fencedMutations.terminalize(
                    authority,AT.plusSeconds(2),null,true,null));
        }
        try(StateStore store=new StateStore(tempDir.resolve("terminal-race-task"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            assertEquals(FencedMutationResult.SUCCESS,store.fencedMutations.terminalize(
                    authority,AT.plusSeconds(1),null,false,"failed"));
            var rejected=store.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(2));
            assertEquals(SteeringStore.Status.TERMINAL_TASK,rejected.status());
            assertEquals(State.REJECTED,rejected.queue().state());
        }
    }

    @Test void materialSteeringCreatesOnlyANextDraftAndKeepsCurrentPlanActive()throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("material"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);
            CanonicalId source=approvePlan(store,seed,authority);
            Target target=store.steerings.captureTarget(seed.task,seed.run).target();
            var steering=store.steerings.admit(target,new SteeringClassifier().classify("change plan step scope"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(2));
            var prepared=store.steerings.applyOldest(authority,AT.plusSeconds(3),
                    "NEXT_STEP_ATTEMPT_BEFORE",false);
            assertEquals(SteeringStore.Status.MATERIAL_PLAN_REQUIRED,prepared.status());
            var applied=store.steerings.applyOldest(authority,AT.plusSeconds(3),
                    "NEXT_STEP_ATTEMPT_BEFORE",false,
                    materialDraft(prepared.materialPlanRequest(),seed.run),null);
            assertEquals(SteeringStore.Status.SUCCESS,applied.status());assertTrue(applied.executionPaused());
            CanonicalId draft=applied.application().planRevisionId();assertNotNull(draft);assertNotEquals(source,draft);
            assertEquals(source,store.planAuthorities.readActive(seed.task).plan().revision().planRevisionId());
            assertEquals(1,scalar(store,"SELECT count(*) FROM plan_revision_lifecycle WHERE plan_revision_id=? AND state='DRAFT'",draft.value()));
            assertEquals(0,scalar(store,"SELECT count(*) FROM plan_step_executions WHERE plan_revision_id=?",draft.value()));
            assertEquals(0,scalar(store,"""
                    SELECT count(*) FROM plan_step_definitions old
                    JOIN plan_step_definitions fresh
                      ON fresh.title=old.title AND fresh.definition=old.definition
                    WHERE old.plan_revision_id=? AND fresh.plan_revision_id=?
                    """,source.value(),draft.value()));
        }
    }

    @Test void materialApplicationUsesExactAttemptCasAndRollsBackOnVersionConflict()
            throws Exception{
        try(StateStore store=new StateStore(tempDir.resolve("material-cas"))){
            Seed seed=seed(store,StructuredPlanModels.PlanApplicability.PLAN_REQUIRED);
            ExecutionAuthoritySnapshot authority=authority(store,seed);
            CanonicalId source=approvePlan(store,seed,authority);
            var started=store.planExecutions.startNextReadyAttempt(authority,AT.plusSeconds(2),
                    7,"R1D_MATERIAL_CAS_TEST");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
            var steering=store.steerings.admit(store.steerings.captureTarget(seed.task,seed.run).target(),
                    new SteeringClassifier().classify("change plan step scope"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",AT.plusSeconds(3));
            var prepared=store.steerings.applyOldest(authority,AT.plusSeconds(4),
                    "PROVIDER_RESPONSE_BEFORE_TOOL",false);
            var stalePause=new SteeringStore.AttemptPause(started.value().attempt(),
                    started.value().step().transitionVersion()+1,11);
            assertEquals(SteeringStore.Status.VERSION_CONFLICT,store.steerings.applyOldest(
                    authority,AT.plusSeconds(4),"PROVIDER_RESPONSE_BEFORE_TOOL",false,
                    materialDraft(prepared.materialPlanRequest(),seed.run),stalePause).status());
            assertEquals("RUNNING",text(store,"SELECT state FROM plan_step_attempts WHERE task_id=?",
                    seed.task.value()));
            assertEquals(0,scalar(store,"SELECT count(*) FROM steering_application_facts WHERE steering_id=?",
                    steering.steeringId().value()));
            var exactPause=new SteeringStore.AttemptPause(started.value().attempt(),
                    started.value().step().transitionVersion(),11);
            assertEquals(SteeringStore.Status.SUCCESS,store.steerings.applyOldest(
                    authority,AT.plusSeconds(5),"PROVIDER_RESPONSE_BEFORE_TOOL",false,
                    materialDraft(prepared.materialPlanRequest(),seed.run),exactPause).status());
            assertEquals("BLOCKED",text(store,"SELECT state FROM plan_step_attempts WHERE task_id=?",
                    seed.task.value()));
            assertEquals("11",text(store,"SELECT end_iteration FROM plan_step_attempts WHERE task_id=?",
                    seed.task.value()));
            assertEquals("BLOCKED",text(store,"SELECT state FROM plan_step_executions WHERE task_id=?",
                    seed.task.value()));
        }
    }

    @Test void queuedSteeringSurvivesRestartButRequiresExplicitResumeRunRebind()throws Exception{
        Path workspace=tempDir.resolve("recovery");Seed seed;CanonicalId steering;
        try(StateStore first=new StateStore(workspace)){
            seed=seed(first,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);authority(first,seed);
            Target target=first.steerings.captureTarget(seed.task,seed.run).target();
            steering=first.steerings.admit(target,new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:old","old",AT.plusSeconds(1)).steeringId();
        }
        try(StateStore resumed=new StateStore(workspace)){
            CanonicalId next=CanonicalId.newId(EntityFamily.RUN);
            var admission=resumed.r1aDurable.admitResumeRun(new DurableRun(next,seed.task,seed.thread,"RESUME",
                    seed.run,seed.manifestId,seed.manifestHash,AT.plusSeconds(61),null),"owner-b","lease-b",
                    AT.plusSeconds(61),AT.plusSeconds(121),Duration.ZERO);
            var success=assertInstanceOf(R1aStoreResults.ResumeRunAdmissionResult.Success.class,admission);
            ExecutionAuthoritySnapshot authority=new ExecutionAuthoritySnapshot(seed.task,next,"owner-b","lease-b",
                    success.ownership().fencingToken(),success.ownership().casVersion());
            assertEquals(SteeringStore.Status.REBIND_REQUIRED,resumed.steerings.applyOldest(authority,
                    AT.plusSeconds(62),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
            Target rebound=resumed.steerings.captureTarget(seed.task,next).target();
            var queue=resumed.steerings.readQueue(steering).orElseThrow();
            assertEquals(SteeringStore.Status.SUCCESS,resumed.steerings.retarget(steering,rebound,
                    "interactive:new","new",queue.version(),AT.plusSeconds(63)).status());
            assertEquals(SteeringStore.Status.SUCCESS,resumed.steerings.applyOldest(authority,
                    AT.plusSeconds(64),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
            assertEquals(State.APPLIED,resumed.steerings.readQueue(steering).orElseThrow().state());
            assertEquals(SteeringStore.Status.NOT_FOUND,resumed.steerings.applyOldest(authority,
                    AT.plusSeconds(65),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
        }
    }

    @Test void queuedSteeringCanApplyAfterProcessRestartWhenTheSameRunAuthorityRemainsValid()throws Exception{
        Path workspace=tempDir.resolve("same-run-recovery");Seed seed;
        try(StateStore first=new StateStore(workspace)){
            seed=seed(first,StructuredPlanModels.PlanApplicability.PLAN_DISABLED);authority(first,seed);
            first.steerings.admit(first.steerings.captureTarget(seed.task,seed.run).target(),
                    new SteeringClassifier().classify("format: concise"),Provenance.INTERACTIVE_EXPLICIT,
                    "interactive:s1","s1",AT.plusSeconds(1));
        }
        try(StateStore restarted=new StateStore(workspace)){
            var lease=restarted.r1aDurable.readOwnership(seed.task).orElseThrow();
            var authority=new ExecutionAuthoritySnapshot(seed.task,seed.run,"owner","lease",
                    lease.fencingToken(),lease.casVersion());
            assertEquals(SteeringStore.Status.SUCCESS,restarted.steerings.applyOldest(authority,
                    AT.plusSeconds(2),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
        }
        try(StateStore afterCommit=new StateStore(workspace)){
            var lease=afterCommit.r1aDurable.readOwnership(seed.task).orElseThrow();
            var authority=new ExecutionAuthoritySnapshot(seed.task,seed.run,"owner","lease",
                    lease.fencingToken(),lease.casVersion());
            assertEquals(SteeringStore.Status.NOT_FOUND,afterCommit.steerings.applyOldest(authority,
                    AT.plusSeconds(3),"NEXT_STEP_ATTEMPT_BEFORE",false).status());
            assertEquals(1,scalar(afterCommit,"SELECT count(*) FROM steering_application_facts WHERE task_id=?",
                    seed.task.value()));
        }
    }

    private CanonicalId approvePlan(StateStore store,Seed seed,ExecutionAuthoritySnapshot authority){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN),step=CanonicalId.newId(EntityFamily.PLAN_STEP);
        var definition=new StructuredPlanModels.StepDefinition(step,CanonicalId.newId(EntityFamily.PLAN_STEP),0,
                "execute","execute safely",PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),PlanCompletionModels.EffectClass.WRITE,
                List.of(exactWriteRequirement("write-a","A.java",true)),"b".repeat(64));
        var zero=new StructuredPlanModels.Revision(plan,seed.task,1,"goal","accept","","","verify",
                "0".repeat(64),"TEST","R1D_TEST",seed.run,AT.plusSeconds(1),List.of(definition),
                List.of(),List.of(),StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        var revision=new StructuredPlanModels.Revision(plan,seed.task,1,zero.goal(),zero.acceptanceCriteria(),
                zero.assumptions(),zero.risks(),zero.verificationStrategy(),PlanCanonicalizer.hash(zero),zero.sourceType(),
                zero.provenance(),seed.run,zero.createdAt(),zero.steps(),zero.dependencies(),zero.lineage(),
                StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,store.planAuthorities.propose(authority,AT.plusSeconds(1),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,store.planAuthorities.validateRevision(authority,AT.plusSeconds(1),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,store.planAuthorities.approveAndActivate(authority,AT.plusSeconds(1),
                plan,1,0,"approval-r1d","tester","v1","approved").status());
        return plan;
    }

    private static StructuredPlanModels.Revision materialDraft(
            SteeringStore.MaterialPlanRequest request,CanonicalId run){
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        CanonicalId step=CanonicalId.newId(EntityFamily.PLAN_STEP);
        String provenance="R1D_STEERING:"+request.definition().steeringId().value();
        var definition=new StructuredPlanModels.StepDefinition(step,
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"execute updated scope",
                "execute the material Steering change safely",
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

    private Seed seed(StateStore state,StructuredPlanModels.PlanApplicability applicability)throws Exception{
        CanonicalId thread=CanonicalId.newId(EntityFamily.THREAD),task=CanonicalId.newId(EntityFamily.TASK),
                run=CanonicalId.newId(EntityFamily.RUN),generation=CanonicalId.newId(EntityFamily.GENERATION);
        String manifest="manifest-"+UUID.randomUUID();ProtectedInstructionArtifact artifact=
                ProtectedInstructionArtifact.inlineUtf8("artifact-"+UUID.randomUUID(),"frozen","v1","TASK_AUTHORITY",AT);
        var entry=new InstructionManifestCandidate.Entry("entry-"+UUID.randomUUID(),"PROJECT_FILE","AGENTS.md","WORKSPACE",
                InstructionManifestCandidate.Status.APPLIED,0,artifact.contentHash(),artifact.id(),"UTF8",null,null);
        var candidate=new InstructionManifestCandidate(1,"root-paicli-agents-v1",List.of(entry));
        var request=new RootAdmissionTransaction.Request(new WorkThread(thread,"workspace","goal","OPEN",AT,AT),
                new DurableTask(task,thread,null,"goal","CREATED",null,null,AT,null),manifest,candidate,List.of(artifact),
                new DurableRun(run,task,thread,"INITIAL",null,manifest,candidate.canonicalHash(),AT,null),
                new TaskContextGeneration(generation,task,null,run,manifest,candidate.canonicalHash(),"COMMITTED","{}",AT,AT),
                new TaskContextPointer(task,generation,run,0),
                new RootAdmissionTransaction.RuntimeTask(task.value(),"goal","mock","model","deny",3,"RUNNING"),
                new RootAdmissionTransaction.RuntimeRun(run.value(),task.value(),"INITIAL","PLANNING","",1,0,"skills-disabled","hooks-disabled"),
                applicability,AT);
        assertInstanceOf(RootAdmissionTransaction.Success.class,state.r1aDurable.admitRootTaskAtomically(request));
        return new Seed(thread,task,run,manifest,candidate.canonicalHash());
    }

    private ExecutionAuthoritySnapshot authority(StateStore state,Seed seed)throws Exception{
        assertEquals(OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(seed.task,"owner",seed.run,"lease",AT,
                AT.plusSeconds(60)));var lease=state.r1aDurable.readOwnership(seed.task).orElseThrow();
        return new ExecutionAuthoritySnapshot(seed.task,seed.run,"owner","lease",lease.fencingToken(),lease.casVersion());
    }
    private static void execute(StateStore store,String sql,String value)throws Exception{
        Connection c=connection(store);try(PreparedStatement s=c.prepareStatement(sql)){s.setString(1,value);s.executeUpdate();}
    }
    private static int scalar(StateStore store,String sql,String... values)throws Exception{
        Connection c=connection(store);try(PreparedStatement s=c.prepareStatement(sql)){
            for(int index=0;index<values.length;index++)s.setString(index+1,values[index]);
            try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}
        }
    }
    private static String text(StateStore store,String sql,String... values)throws Exception{
        Connection c=connection(store);try(PreparedStatement s=c.prepareStatement(sql)){
            for(int index=0;index<values.length;index++)s.setString(index+1,values[index]);
            try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getString(1);}
        }
    }
    private static Connection connection(StateStore store)throws Exception{
        var field=StateStore.class.getDeclaredField("databaseManager");field.setAccessible(true);
        return ((DatabaseManager)field.get(store)).getConnection();
    }
    private record Seed(CanonicalId thread,CanonicalId task,CanonicalId run,
            String manifestId,String manifestHash){}
}
