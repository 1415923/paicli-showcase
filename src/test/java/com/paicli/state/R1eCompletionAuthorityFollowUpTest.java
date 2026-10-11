package com.paicli.state;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.ToolCall;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class R1eCompletionAuthorityFollowUpTest {
    @TempDir Path temp;
    private static final Instant AT=Instant.parse("2026-07-20T00:00:00Z");
    private static final String HASH="a".repeat(64);

    @Test void validR1cCompletionAllowsFollowUpAndCorruptAuthorityCreatesNoCapsuleOrChild()throws Exception{
        Path valid=temp.resolve("valid");try(StateStore state=new StateStore(valid)){
            Seed seed=completedPlanParent(state,valid);
            var outcome=service(state,valid).begin(seed.thread(),"continue safely",true,"owner","session",AT.plusSeconds(10));
            assertNotNull(outcome.child());assertEquals(1,count(valid,"continuation_capsules"));
        }
        for(String corruption:List.of("EVIDENCE_HASH","PRODUCER_SCOPE","EVALUATION_INPUT","RULE","REQUIREMENT",
                "DECISION_HASH","REVISION_SCOPE","RUN_SCOPE")){
            Path path=temp.resolve(corruption.toLowerCase());try(StateStore state=new StateStore(path)){
                Seed seed=completedPlanParent(state,path);corrupt(path,corruption);
                RuntimeException rejected=assertThrows(RuntimeException.class,()->service(state,path).begin(seed.thread(),
                        "must fail closed",true,"owner","session",AT.plusSeconds(10)));
                assertTrue(rejected.getMessage().contains("COMPLETION_INTEGRITY_FAILURE")
                        ||rejected.getMessage().contains("Plan completion Recovery read failed"));
                assertEquals(0,count(path,"continuation_capsules"));
                assertEquals(0,countWhere(path,"task_identities","parent_task_id IS NOT NULL"));
            }
        }
        Path verification=temp.resolve("verification-association");
        try(StateStore state=new StateStore(verification)){
            Seed seed=completedPlanParent(state,verification,true);
            try(DatabaseManager manager=new DatabaseManager(verification.resolve(".paicli/state"));
                    Statement statement=manager.getConnection().createStatement()){
                statement.execute("DROP TRIGGER r1c_verification_evidence_no_delete");
                statement.executeUpdate("DELETE FROM plan_verification_evidence");
            }
            RuntimeException rejected=assertThrows(RuntimeException.class,()->service(state,verification).begin(
                    seed.thread(),"must fail closed",true,"owner","session",AT.plusSeconds(12)));
            assertTrue(rejected.getMessage().contains("COMPLETION_INTEGRITY_FAILURE"));
            assertEquals(0,count(verification,"continuation_capsules"));
            assertEquals(0,countWhere(verification,"task_identities","parent_task_id IS NOT NULL"));
        }
    }

    private Seed completedPlanParent(StateStore state,Path workspace)throws Exception{
        return completedPlanParent(state,workspace,false);
    }

    private Seed completedPlanParent(StateStore state,Path workspace,boolean verification)throws Exception{
        Config config=config(workspace,verification);CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        InstructionManifestRuntime manifests=new InstructionManifestRuntime(state,workspace);
        var strict=InstructionManifestRuntime.class.getDeclaredMethod("strictPlanAuthorityForTesting");
        strict.setAccessible(true);
        manifests=(InstructionManifestRuntime)strict.invoke(manifests);
        var root=manifests.admitRoot(task,"parent goal",10_000,config,
                RuntimeManifest.disabled(),AT);CanonicalId run=root.initialRun().id();
        assertEquals(R1aStoreResults.OwnershipResult.SUCCESS,state.r1aDurable.acquireOwnership(task,"owner",run,
                "lease",AT,AT.plusSeconds(120)));
        var lease=state.r1aDurable.readOwnership(task).orElseThrow();
        ExecutionAuthoritySnapshot authority=new ExecutionAuthoritySnapshot(task,run,lease.ownerInstanceId(),
                lease.leaseToken(),lease.fencingToken(),lease.casVersion());
        state.codingRuns.transitionPhase(run.value(),CodingRunStore.PHASE_PLANNING,CodingRunStore.PHASE_EXECUTING);
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition step=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,"one","do one tool effect",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(verification?PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT:
                        PlanCompletionModels.EvidenceKind.TOOL_CALL),
                verification?PlanCompletionModels.EffectClass.VERIFY:PlanCompletionModels.EffectClass.READ,
                verification?List.of():List.of(exactReadRequirement()),
                "b".repeat(64));
        Revision candidate=new Revision(plan,task,1,"parent goal","done","","","verify","0".repeat(64),
                "USER","test",run,AT,List.of(step),List.of(),List.of(),
                Revision.CANONICAL_SCHEMA_V3);
        Revision revision=new Revision(plan,task,1,candidate.goal(),candidate.acceptanceCriteria(),candidate.assumptions(),
                candidate.risks(),candidate.verificationStrategy(),PlanCanonicalizer.hash(candidate),candidate.sourceType(),
                candidate.provenance(),run,AT,candidate.steps(),candidate.dependencies(),candidate.lineage(),
                Revision.CANONICAL_SCHEMA_V3);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.propose(authority,AT.plusSeconds(1),revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.validateRevision(authority,AT.plusSeconds(2),plan,0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(authority,AT.plusSeconds(3),plan,
                1,0,"approval","user","v1",null).status());
        var started=state.planExecutions.startNextReadyAttempt(authority,AT.plusSeconds(4),1,"test");
        if(!verification){
            String toolCallId="read-agents-call";
            ToolCall call=new ToolCall("read_file",Map.of("path","AGENTS.md"),toolCallId);
            var requirement=step.toolRequirements().getFirst();
            var request=new FencedMutationStore.PlanToolOccurrenceRequest(toolCallId,"read_file",
                    PlanCompletionModels.EffectClass.READ.name(),
                    requirement.authoritySelector().digest(),requirement.requirementId(),1,"1",
                    "follow-up-tool:"+toolCallId,
                    com.paicli.runtime.FencedExecutionGuard.canonicalToolCallHash(call));
            var reservation=state.fencedMutations.reservePlanToolBatch(authority,AT.plusSeconds(5),
                    plan,step.stepRevisionId(),started.value().attempt().attemptNumber(),List.of(request));
            assertEquals(R1aStoreResults.FencedMutationResult.SUCCESS,reservation.result());
            assertEquals(1,reservation.occurrenceIds().size());
            CanonicalId occurrence=reservation.occurrenceIds().getFirst();
            assertEquals(R1aStoreResults.FencedMutationResult.SUCCESS,state.fencedMutations.transitionOccurrence(authority,
                    AT.plusSeconds(6),occurrence,"COMPLETED",HASH,"KNOWN_SUCCESS"));
        }
        assertEquals(PlanExecutionStore.Status.SUCCESS,state.planExecutions.completeAttempt(authority,AT.plusSeconds(7),
                started.value().attempt(),Outcome.SUCCEEDED,2,started.value().step().transitionVersion(),null,"done").status());
        if(verification){
            assertEquals(R1aStoreResults.FencedMutationResult.SUCCESS,state.fencedMutations.startVerification(
                    authority,AT.plusSeconds(8),"verify-passed",0,"verify","RUNNING",
                    "WORKSPACE_CONTENT_SHA256_V1","a".repeat(64),null));
            assertEquals(R1aStoreResults.FencedMutationResult.SUCCESS,state.fencedMutations.finishVerification(
                    authority,AT.plusSeconds(9),"verify-passed","PASSED",0,"ok","",1));
        }
        assertEquals(PlanCompletionModels.CompletionResult.COMPLETE,state.planCompletions.evaluateAndComplete(authority,
                AT.plusSeconds(10),plan).result());
        return new Seed(root.threadId(),task,run,plan);
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

    private static void corrupt(Path workspace,String kind)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));Connection c=manager.getConnection();
                Statement s=c.createStatement()){
            switch(kind){
                case "EVIDENCE_HASH"->{s.execute("DROP TRIGGER r1c_evidence_immutable");
                    s.executeUpdate("UPDATE plan_evidence SET canonical_hash='"+"0".repeat(64)+"'");}
                case "PRODUCER_SCOPE"->{s.execute("DROP TRIGGER r1c_evidence_immutable");
                    s.execute("PRAGMA foreign_keys=OFF");
                    s.executeUpdate("UPDATE plan_evidence SET producer_identity='occ_0123456789ab4def8abc123456789abc'");}
                case "EVALUATION_INPUT"->{s.execute("DROP TRIGGER r1c_completion_evaluation_steps_immutable");
                    s.executeUpdate("UPDATE plan_completion_evaluation_steps SET transition_version=transition_version+1");}
                case "RULE"->{s.execute("DROP TRIGGER r1c_completion_rule_immutable");
                    s.executeUpdate("UPDATE plan_completion_rules SET canonical_hash='"+"0".repeat(64)+"'");}
                case "REQUIREMENT"->{s.execute("DROP TRIGGER r1c_completion_requirement_immutable");
                    s.executeUpdate("UPDATE plan_completion_requirements SET required_evidence_kind='SNAPSHOT' "
                            +"WHERE requirement_type='EVIDENCE_KIND_PRESENT'");}
                case "DECISION_HASH"->{s.execute("DROP TRIGGER r1c_completion_decision_immutable");
                    s.executeUpdate("UPDATE task_completion_decisions SET decision_hash='"+"0".repeat(64)+"'");}
                case "REVISION_SCOPE"->{s.execute("PRAGMA foreign_keys=OFF");
                    s.executeUpdate("UPDATE task_plan_authorities SET active_plan_revision_id='"
                            +CanonicalId.newId(EntityFamily.PLAN).value()+"'");}
                case "RUN_SCOPE"->{s.execute("PRAGMA foreign_keys=OFF");
                    s.executeUpdate("UPDATE task_context_pointers SET owner_run_id='"
                            +CanonicalId.newId(EntityFamily.RUN).value()+"'");}
                default->throw new IllegalArgumentException(kind);
            }
        }
    }
    private FollowUpService service(StateStore state,Path workspace){Config config=config(workspace,false);return new FollowUpService(
            state,new InstructionManifestRuntime(state,workspace),config,RuntimeManifest.disabled(),10_000);}
    private static Config config(Path workspace,boolean verification){return new Config(workspace.toString(),30,50_000,200_000,
            Config.MODE_WORKSPACE_WRITE,10_000,Config.PROVIDER_MOCK,"model","","","system",10,
            verification?"verify":"",120,50_000,1,true,verification,false);}
    private static int count(Path workspace,String table)throws Exception{return countWhere(workspace,table,"1=1");}
    private static int countWhere(Path workspace,String table,String where)throws Exception{try(DatabaseManager manager=
            new DatabaseManager(workspace.resolve(".paicli/state"));var r=manager.getConnection().createStatement()
            .executeQuery("SELECT count(*) FROM "+table+" WHERE "+where)){r.next();return r.getInt(1);}}
    private record Seed(CanonicalId thread,CanonicalId task,CanonicalId run,CanonicalId revision){ }
}
