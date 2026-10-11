package com.paicli.demo.workflow;

import com.paicli.agent.*;
import com.paicli.demo.provider.CoreDemoDeterministicProvider;
import com.paicli.demo.runtime.*;
import com.paicli.demo.scenario.*;
import com.paicli.demo.verification.*;
import com.paicli.runtime.*;
import com.paicli.state.*;
import com.paicli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.*;

public final class CoreDemoResumeService {
    private final CoreDemoScenarioContractLoader contracts;
    private final CoreDemoVerificationClassifier classifier;

    public CoreDemoResumeService() {
        this(new CoreDemoScenarioContractLoader(),new CoreDemoVerificationClassifier());
    }
    CoreDemoResumeService(CoreDemoScenarioContractLoader contracts,
            CoreDemoVerificationClassifier classifier) {
        this.contracts=Objects.requireNonNull(contracts);
        this.classifier=Objects.requireNonNull(classifier);
    }

    public CoreDemoResumeResult resume(CoreDemoRunContext context) throws Exception {
        CoreDemoScenarioContract c=contracts.load().contract();
        StateStore store=context.stateStore();
        String owner="resume-"+UUID.randomUUID().toString().replace("-","");
        store.coreDemoRuns.beginResume(context.runId(),owner);
        try {
            Facts facts=validate(context,c);
            CoreDemoResumeResult.SideEffectCounts before=counts(store,context.runId(),facts.taskId(),facts.codingRunId());
            store.coreDemoRuns.markResumeRunning(context.runId(),owner);

            CoreDemoDeterministicProvider provider=CoreDemoDeterministicProvider.restoreForRepair(
                    c,context.workspaceIdentityHash(),facts.checkpoint(),facts.initialTreeHash());
            FileSnapshotService snapshotService=new FileSnapshotService(
                    context.workspace().workspace(),store.snapshots);
            ToolRegistry registry=CoreDemoPrimaryWorkflow.registry(context,facts.taskId(),snapshotService);
            AgentLoop loop=new AgentLoop(provider,registry,context.budgets().maxWorkflowTurns(),
                    CoreDemoPrimaryWorkflow.approvalPolicy(context,facts.taskId()),null,store,
                    facts.taskId(),"core-demo-deterministic",c.providerScriptVersion());

            store.codingRuns.transitionPhase(facts.codingRunId(),
                    CodingRunStore.PHASE_VERIFYING,CodingRunStore.PHASE_REPAIRING);
            String repairId="repair-"+UUID.randomUUID().toString().replace("-","");
            store.repairAttempts.recordStarted(repairId,facts.codingRunId(),facts.taskId(),1,
                    facts.firstRecord().verificationId(),
                    "ASSERTION_FAILURE:"+facts.firstEvidence().evidenceHash());
            String prompt=CoreDemoPrimaryWorkflow.prompt("DEMO_SCENARIO_ID",c.scenarioId())
                    +"\nDEMO_TASK_ID="+facts.taskId()
                    +"\n"+CoreDemoPrimaryWorkflow.prompt("DEMO_WORKSPACE_IDENTITY_SHA256",
                            context.workspaceIdentityHash())
                    +"\n"+CoreDemoPrimaryWorkflow.prompt("DEMO_VERIFICATION_EVIDENCE_SHA256",
                            facts.firstEvidence().evidenceHash())
                    +"\nStructured failure: "
                    +facts.firstEvidence().failedTests().getFirst().className()+"#"
                    +facts.firstEvidence().failedTests().getFirst().methodName();
            AgentSegmentResult repaired=loop.runSegment(new AgentSegmentRequest(
                    c.fixedTaskText(),List.of(),prompt,
                    facts.codingRun().lastIteration(),4));
            if(!repaired.completed())
                throw drift("DEMO_REPAIR_DRIFT","resume repair segment incomplete");
            store.codingRuns.updateLastIteration(facts.codingRunId(),repaired.endIteration());
            store.repairAttempts.markCompleted(repairId);
            int repairs=store.codingRuns.incrementRepairAttempts(facts.codingRunId());
            if(repairs!=1)throw drift("DEMO_REPAIR_DRIFT","repair count mismatch");

            String finalHash=CoreDemoPrimaryWorkflow.hashAndValidate(
                    context.workspace().workspace(),context.fixtureManifest(),c,
                    c.expectedFinalTreeHash(),"DEMO_FINAL_TREE_DRIFT");
            List<FileSnapshotStore.FileSnapshotRecord> snapshots=
                    CoreDemoPrimaryWorkflow.appliedSnapshots(store,facts.taskId());
            if(snapshots.size()!=2||!snapshots.getFirst().snapshotId()
                    .equals(facts.checkpoint().snapshotIds().getFirst()))
                throw drift("DEMO_SNAPSHOT_MISSING","resume snapshot order mismatch");

            store.codingRuns.transitionPhase(facts.codingRunId(),
                    CodingRunStore.PHASE_REPAIRING,CodingRunStore.PHASE_VERIFYING);
            VerificationService verifier=new VerificationService(context.workspace().workspace(),
                    store.verifications,new PersistenceSanitizer(),
                    (int)context.budgets().verificationTimeoutSeconds(),32768);
            VerificationResult second=verifier.verify("verify-"+UUID.randomUUID(),
                    facts.codingRunId(),facts.taskId(),1,CoreDemoPrimaryWorkflow.COMMAND);
            CoreDemoVerificationEvidence finalEvidence=classifier.classify(
                    context.workspace().workspace(),"REPAIR_VERIFICATION",second,finalHash,
                    facts.checkpoint().verifierConfigHash());
            CoreDemoPrimaryWorkflow.requireFinal(second,finalEvidence,c);

            store.codingRuns.markCompleted(facts.codingRunId());
            store.plans.markCompleted(facts.plan().planId());
            store.tasks.markDone(facts.taskId());
            CoreDemoResumeResult.SideEffectCounts after=counts(store,context.runId(),facts.taskId(),facts.codingRunId());
            requireNoDuplicate(before,after,store.toolCalls.findByTask(facts.taskId()));
            store.coreDemoRuns.markResumeSucceeded(context.runId(),owner,finalHash,1);
            List<String> tools=store.toolCalls.findByTask(facts.taskId()).stream()
                    .map(ToolCallStore.ToolCallRecord::toolName).toList();
            return new CoreDemoResumeResult(facts.taskId(),facts.codingRunId(),owner,
                    facts.checkpoint().planHash(),facts.checkpoint().codeIntelligenceHash(),
                    facts.checkpoint().semanticHash(),facts.initialTreeHash(),finalHash,
                    facts.firstEvidence(),finalEvidence,
                    snapshots.stream().map(FileSnapshotStore.FileSnapshotRecord::snapshotId).toList(),
                    tools,before,after,true);
        } catch(Exception error) {
            try{store.coreDemoRuns.markResumeFailed(context.runId(),owner,
                    error instanceof CoreDemoScenarioDriftException d?d.category()
                            :"DEMO_RESUME_EVIDENCE_DRIFT",error.getMessage());}
            catch(Exception ignored){}
            throw error;
        }
    }

    private Facts validate(CoreDemoRunContext context,CoreDemoScenarioContract c)throws Exception {
        StateStore store=context.stateStore();
        CoreDemoRunStore.CoreDemoRunRecord run=store.coreDemoRuns.findById(context.runId())
                .orElseThrow(()->drift("DEMO_RESUME_EVIDENCE_DRIFT","run missing"));
        if(!run.scenarioId().equals(c.scenarioId())
                ||!run.fixtureManifestHash().equals(context.fixtureManifestHash())
                ||!run.seedTreeHash().equals(c.expectedBaselineTreeHash())
                ||!run.workspaceIdentityHash().equals(context.workspaceIdentityHash())
                ||!CoreDemoRunStore.RESUME_VALIDATING.equals(run.resumeStatus())
                ||run.resumed()||run.repairCount()!=0)
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","run identity/state drift");

        CoreDemoCheckpointStore.CheckpointRecord checkpoint=store.coreDemoCheckpoints
                .findByRun(context.runId())
                .orElseThrow(()->drift("DEMO_RESUME_EVIDENCE_DRIFT","checkpoint missing"));
        if(!"VERIFY_FAILED_BEFORE_REPAIR".equals(checkpoint.phase())
                ||!checkpoint.taskId().equals(run.taskId())
                ||!checkpoint.workflowOccurrenceKey().equals(run.workflowOccurrenceKey())
                ||!checkpoint.initialTreeHash().equals(c.expectedInitialImplementationTreeHash())
                ||!checkpoint.providerScriptVersion().equals(c.providerScriptVersion())
                ||!checkpoint.approvalProfileVersion().equals(c.approvalProfileVersion())
                ||!checkpoint.changedFiles().equals(c.expectedChangedFiles()))
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","checkpoint drift");

        String initial=CoreDemoPrimaryWorkflow.hashAndValidate(context.workspace().workspace(),
                context.fixtureManifest(),c,c.expectedInitialImplementationTreeHash(),
                "DEMO_RESUME_EVIDENCE_DRIFT");
        List<CodingRunStore.CodingRunRecord> codingRuns=store.codingRuns.findByTask(run.taskId());
        if(codingRuns.size()!=1)throw drift("DEMO_RESUME_EVIDENCE_DRIFT","coding run not unique");
        CodingRunStore.CodingRunRecord coding=codingRuns.getFirst();
        if(!CodingRunStore.KIND_INITIAL.equals(coding.runKind())
                ||!CodingRunStore.PHASE_VERIFYING.equals(coding.phase())
                ||coding.repairAttemptsCompleted()!=0)
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","coding run state drift");
        TaskPlanStore.TaskPlanRecord plan=store.plans.findByRun(coding.runId())
                .orElseThrow(()->drift("DEMO_RESUME_EVIDENCE_DRIFT","plan missing"));
        if(!TaskPlanStore.STATUS_ACTIVE.equals(plan.status())
                ||!CoreDemoContractHash.sha256(plan.planText()).equals(checkpoint.planHash()))
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","plan drift");

        List<VerificationStore.VerificationRecord> verifications=
                store.verifications.findByRun(coding.runId());
        if(verifications.size()!=1)
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","first verification not unique");
        VerificationStore.VerificationRecord first=verifications.getFirst();
        VerificationResult firstResult=new VerificationResult(first.verificationId(),first.runId(),
                first.taskId(),first.repairAttempt(),first.command(),first.status(),first.exitCode(),
                first.durationMs()==null?0:first.durationMs(),first.outputExcerpt(),first.errorExcerpt(),
                first.candidateDigestKind(),first.candidateDigest());
        CoreDemoVerificationEvidence evidence=classifier.classify(context.workspace().workspace(),
                "FIRST_VERIFICATION",firstResult,initial,checkpoint.verifierConfigHash());
        if(!VerificationStore.STATUS_FAILED.equals(first.status())
                ||!evidence.evidenceHash().equals(checkpoint.verificationEvidenceHash())
                ||evidence.tests()!=3||evidence.failures()!=1||evidence.errors()!=0
                ||evidence.skipped()!=0||evidence.failedTests().size()!=1
                ||!evidence.failedTests().getFirst().methodName()
                        .equals(c.expectedFirstVerification().failingTestName()))
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","first verification evidence drift");

        List<FileSnapshotStore.FileSnapshotRecord> snapshots=store.snapshots.findByTask(run.taskId());
        if(snapshots.size()!=1||!FileSnapshotStore.STATUS_APPLIED.equals(snapshots.getFirst().status())
                ||!checkpoint.snapshotIds().equals(List.of(snapshots.getFirst().snapshotId())))
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","initial snapshot drift");
        List<String> tools=store.toolCalls.findByTask(run.taskId()).stream()
                .map(ToolCallStore.ToolCallRecord::toolName).toList();
        if(!tools.equals(List.of("list_files","grep","read_file","write_file")))
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","initial tool sequence drift");
        if(!store.repairAttempts.findByRun(coding.runId()).isEmpty())
            throw drift("DEMO_RESUME_EVIDENCE_DRIFT","repair already started");
        return new Facts(run.taskId(),coding.runId(),coding,plan,checkpoint,first,evidence,initial);
    }

    private static CoreDemoResumeResult.SideEffectCounts counts(StateStore store,
            String demoRunId,String taskId,String codingRunId)throws Exception {
        List<ToolCallStore.ToolCallRecord> calls=store.toolCalls.findByTask(taskId);
        List<VerificationStore.VerificationRecord> verify=store.verifications.findByRun(codingRunId);
        return new CoreDemoResumeResult.SideEffectCounts(
                store.plans.findByRun(codingRunId).isPresent()?1:0,
                store.codeSearchAudits.findRecentAudits(1000).size(),
                (int)calls.stream().filter(x->"write_file".equals(x.toolName())).count()>0?1:0,
                (int)verify.stream().filter(x->x.repairAttempt()==0).count(),
                store.coreDemoCheckpoints.findByRun(demoRunId).isPresent()?1:0,
                store.snapshots.findByTask(taskId).size(),
                store.repairAttempts.findByRun(codingRunId).size(),
                Math.max(0,(int)calls.stream().filter(x->"write_file".equals(x.toolName())).count()-1),
                (int)verify.stream().filter(x->x.repairAttempt()==1).count());
    }

    private static void requireNoDuplicate(CoreDemoResumeResult.SideEffectCounts before,
            CoreDemoResumeResult.SideEffectCounts after,List<ToolCallStore.ToolCallRecord> calls) {
        if(before.plans()!=1||after.plans()!=1
                ||before.codeAudits()!=after.codeAudits()
                ||before.initialWrites()!=1||after.initialWrites()!=1
                ||before.firstVerifications()!=1||after.firstVerifications()!=1
                ||before.checkpoints()!=1||after.checkpoints()!=1
                ||before.snapshots()!=1||after.snapshots()!=2
                ||before.repairAttempts()!=0||after.repairAttempts()!=1
                ||before.repairWrites()!=0||after.repairWrites()!=1
                ||before.finalVerifications()!=0||after.finalVerifications()!=1
                ||!calls.stream().map(ToolCallStore.ToolCallRecord::toolName).toList().equals(
                        List.of("list_files","grep","read_file","write_file",
                                "read_file","write_file")))
            throw drift("DEMO_RESUME_DUPLICATE_SIDE_EFFECT","resume side-effect counts drift");
    }

    private static CoreDemoScenarioDriftException drift(String category,String message){
        return new CoreDemoScenarioDriftException(category,message);
    }
    private record Facts(String taskId,String codingRunId,
            CodingRunStore.CodingRunRecord codingRun,TaskPlanStore.TaskPlanRecord plan,
            CoreDemoCheckpointStore.CheckpointRecord checkpoint,
            VerificationStore.VerificationRecord firstRecord,
            CoreDemoVerificationEvidence firstEvidence,String initialTreeHash){}
}
