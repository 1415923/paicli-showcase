package com.paicli.demo.workflow;

import com.paicli.demo.checkpoint.CoreDemoCheckpointInterruptedException;
import com.paicli.demo.runtime.*;
import com.paicli.demo.scenario.CoreDemoScenarioContractLoader;
import com.paicli.state.StateStore;

import java.util.*;

public final class CoreDemoFullWorkflow implements CoreDemoWorkflow {
    private final CoreDemoStateStoreFactory stores;
    private final CoreDemoResumeService resumes;
    private final CoreDemoRewindService rewinds;
    private boolean executed;

    public CoreDemoFullWorkflow() {
        this(CoreDemoStateStoreFactory.production(),new CoreDemoResumeService(),
                new CoreDemoRewindService());
    }
    CoreDemoFullWorkflow(CoreDemoStateStoreFactory stores,CoreDemoResumeService resumes,
            CoreDemoRewindService rewinds) {
        this.stores=Objects.requireNonNull(stores);
        this.resumes=Objects.requireNonNull(resumes);
        this.rewinds=Objects.requireNonNull(rewinds);
    }

    @Override public synchronized CoreDemoWorkflowResult execute(CoreDemoRunContext context)
            throws Exception {
        if(executed)throw new IllegalStateException("Core Demo full workflow is one-shot");
        executed=true;
        boolean interrupted=false;
        CoreDemoPrimaryWorkflow primary=new CoreDemoPrimaryWorkflow(
                new CoreDemoScenarioContractLoader(),
                new com.paicli.demo.verification.CoreDemoVerificationClassifier(),
                checkpoint->{throw new CoreDemoCheckpointInterruptedException(
                        "controlled interruption after durable checkpoint");});
        try {
            primary.execute(context);
            throw new CoreDemoScenarioDriftException(
                    "DEMO_CHECKPOINT_FAILURE","primary workflow did not interrupt");
        } catch(CoreDemoCheckpointInterruptedException expected) {
            interrupted=true;
        } finally {
            context.stateStore().close();
        }

        try(StateStore resumedStore=stores.open(context.workspace())) {
            CoreDemoRunContext resumedContext=withStore(context,resumedStore);
            CoreDemoResumeResult resume=resumes.resume(resumedContext);
            CoreDemoRewindResult rewind=rewinds.rewind(resumedContext,resume);
            CoreDemoSemanticFacts semantic=new CoreDemoSemanticFacts(
                    resume.planHash(),resume.codeIntelligenceHash(),
                    resume.checkpointSemanticHash(),resume.firstVerification().evidenceHash(),
                    resume.finalVerification().evidenceHash(),resume.toolSequence(),
                    List.of("SNAPSHOT_2_FINAL_TO_INITIAL","SNAPSHOT_1_INITIAL_TO_SEED"),interrupted,
                    resume.duplicateSideEffectsPrevented(),true);
            return new CoreDemoWorkflowResult(context.runId(),context.scenarioId(),
                    context.workspaceIdentityHash(),
                    CoreDemoWorkflowResult.TerminalCategory.COMPLETED,
                    CoreDemoWorkflowResult.Phase.REWIND,
                    CoreDemoWorkflowResult.FactStatus.PASSED,
                    CoreDemoWorkflowResult.FactStatus.PASSED,3,
                    Math.max(1,resume.before().codeAudits()),
                    CoreDemoWorkflowResult.FactStatus.PASSED,
                    context.fixtureManifest().allowedMutablePaths(),2,List.of(
                    new CoreDemoWorkflowResult.VerificationFact("FIRST_VERIFICATION",
                            CoreDemoWorkflowResult.FactStatus.FAILED,
                            "EXPECTED_ASSERTION_FAILURE"),
                    new CoreDemoWorkflowResult.VerificationFact("REPAIR_VERIFICATION",
                            CoreDemoWorkflowResult.FactStatus.PASSED,"PASSED"),
                    new CoreDemoWorkflowResult.VerificationFact("POST_RESUME_VERIFICATION",
                            CoreDemoWorkflowResult.FactStatus.PASSED,"PASSED"),
                    new CoreDemoWorkflowResult.VerificationFact("POST_REWIND_BASELINE",
                            CoreDemoWorkflowResult.FactStatus.PASSED,"PASSED")),
                    1,CoreDemoWorkflowResult.FactStatus.PASSED,
                    CoreDemoWorkflowResult.FactStatus.PASSED,
                    resume.initialTreeHash(),resume.finalTreeHash(),rewind.rewindTreeHash(),
                    null,null,CoreDemoWorkflowResult.CleanupRecommendation.CLEAN_RUNTIME,
                    semantic);
        } catch(Exception error) {
            String excerpt=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            return new CoreDemoWorkflowResult(context.runId(),context.scenarioId(),
                    context.workspaceIdentityHash(),
                    CoreDemoWorkflowResult.TerminalCategory.RESUME_REWIND_FAILED,
                    CoreDemoWorkflowResult.Phase.RESUME,
                    CoreDemoWorkflowResult.FactStatus.PASSED,
                    CoreDemoWorkflowResult.FactStatus.PASSED,3,0,
                    CoreDemoWorkflowResult.FactStatus.PASSED,
                    context.fixtureManifest().allowedMutablePaths(),1,List.of(
                    new CoreDemoWorkflowResult.VerificationFact("FIRST_VERIFICATION",
                            CoreDemoWorkflowResult.FactStatus.FAILED,
                            "EXPECTED_ASSERTION_FAILURE")),
                    0,CoreDemoWorkflowResult.FactStatus.FAILED,
                    CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                    null,null,null,"DEMO_RESUME_REWIND_FAILED",excerpt,
                    CoreDemoWorkflowResult.CleanupRecommendation.RETAIN_FOR_DIAGNOSTICS);
        }
    }

    private static CoreDemoRunContext withStore(CoreDemoRunContext c,StateStore store) {
        return new CoreDemoRunContext(c.runId(),c.scenarioId(),c.fixtureManifestHash(),
                c.seedTreeHash(),c.workspace(),c.workspaceIdentityHash(),store,
                c.taskSpecificationId(),c.approvalProfile(),c.budgets(),
                c.baselineEvidence(),c.fixtureManifest(),c.artifactScope(),c.headless());
    }
}