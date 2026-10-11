package com.paicli.demo.runtime;

import com.paicli.demo.approval.CoreDemoApprovalProfile;
import com.paicli.demo.artifact.*;
import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import com.paicli.demo.workspace.*;
import com.paicli.state.*;

import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/**
 * Headless Core Demo orchestration for preparation, durable workflow state,
 * verified artifacts and ownership-checked cleanup.
 */
public final class HeadlessCoreDemoRunner {
    private static final String SCENARIO="core-java-member-discount-v1";
    private final CoreDemoPreparationGateway preparation;
    private final CoreDemoWorkspaceService workspaces;
    private final CoreDemoWorkflow workflow;
    private final CoreDemoArtifactSink artifacts;
    private final CoreDemoStateStoreFactory stores;
    private final Supplier<String> runIds;
    private final AfterArtifactHook afterArtifactHook;

    public HeadlessCoreDemoRunner(CoreDemoPreparationGateway preparation,
            CoreDemoWorkspaceService workspaces, CoreDemoWorkflow workflow,
            CoreDemoArtifactSink artifacts) {
        this(preparation,workspaces,workflow,artifacts,
                CoreDemoStateStoreFactory.production(),
                () -> "run-"+UUID.randomUUID().toString().replace("-","").substring(0,16),
                () -> { });
    }

    HeadlessCoreDemoRunner(CoreDemoPreparationGateway preparation,
            CoreDemoWorkspaceService workspaces,CoreDemoWorkflow workflow,
            CoreDemoArtifactSink artifacts,CoreDemoStateStoreFactory stores,
            Supplier<String> runIds, AfterArtifactHook afterArtifactHook){
        this.preparation=Objects.requireNonNull(preparation);
        this.workspaces=Objects.requireNonNull(workspaces);
        this.workflow=Objects.requireNonNull(workflow);
        this.artifacts=Objects.requireNonNull(artifacts);
        this.stores=Objects.requireNonNull(stores);
        this.runIds=Objects.requireNonNull(runIds);
        this.afterArtifactHook=Objects.requireNonNull(afterArtifactHook);
    }

    public CoreDemoRunResult run(CoreDemoRunRequest request){
        Objects.requireNonNull(request,"request");
        if(!request.headless())return result("run-invalid",CoreDemoExitCode.USAGE,
                "USAGE_ERROR",false,"INVALID_REQUEST","Core Demo requires headless mode");
        String runId=runIds.get();
        Holder holder=new Holder();
        CoreDemoPreparationService.CoreDemoPreparation prepared;
        try{
            prepared=preparation.prepareForRun(runId,new CoreDemoPreparationService.PreparationObserver(){
                @Override public void allocated(CoreDemoWorkspace workspace,
                        CoreDemoFixtureManifestLoader.LoadedManifest loaded)throws Exception{
                    holder.workspace=workspace;holder.loaded=loaded;
                    holder.store=stores.open(workspace);
                    holder.store.coreDemoRuns.create(new CoreDemoRunStore.CreateRun(
                            runId,loaded.manifest().scenarioId(),loaded.manifest().fixtureVersion(),
                            loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash(),
                            workspace.workspaceIdentityHash(),"DETERMINISTIC_SCRIPTED"));
                }
                @Override public void phase(CoreDemoWorkspace workspace,String phase)throws Exception{
                    holder.store.coreDemoRuns.recordPreparationPhase(runId,phase);
                }
            });
        }catch(Exception error){
            if(holder.store!=null)try{
                holder.store.coreDemoRuns.retainForDiagnostics(
                        runId,"PREPARATION_ERROR",error.getMessage());
            }catch(Exception ignored){}
            closeQuietly(holder.store);
            return result(runId,CoreDemoExitCode.INTERNAL_ERROR,"PREPARATION_FAILED",
                    holder.workspace!=null,"PREPARATION_ERROR",error.getMessage());
        }
        if(holder.store==null||holder.workspace==null||holder.loaded==null){
            return result(runId,CoreDemoExitCode.INTERNAL_ERROR,"INTERNAL_ERROR",
                    true,"RUN_STATE_MISSING","Preparation did not establish durable state");
        }
        try{
            CoreDemoBaselineResult baseline=prepared.baselineResult();
            if(!prepared.readyForHeadlessRunner()){
                holder.store.coreDemoRuns.recordBaselineFailed(runId,
                        baseline==null?0:baseline.tests(),
                        baseline==null?"BASELINE_ERROR":baseline.status().name(),
                        baseline==null?"Baseline evidence unavailable":baseline.boundedExcerpt());
                return writeTerminal(holder,baseline==null?fallbackBaseline():baseline,
                        null,CoreDemoExitCode.BASELINE_INVALID,"BASELINE_INVALID",true);
            }
            holder.store.coreDemoRuns.recordBaselinePassed(runId,baseline.tests(),baseline.sourceTreeHash());
            holder.store.coreDemoRuns.startWorkflow(runId);
            CoreDemoWorkflowResult workflowResult;
            CoreDemoRunContext context=null;
            boolean closeAttempted=false;
            try{
                CoreDemoApprovalProfile approval=CoreDemoApprovalProfile.derive(
                        prepared.workspace(),holder.loaded.manifest());
                context=new CoreDemoRunContext(
                        runId,holder.loaded.manifest().scenarioId(),holder.loaded.manifestHash(),
                        holder.loaded.manifest().expectedBaselineTreeHash(),prepared.workspace(),
                        prepared.workspace().workspaceIdentityHash(),holder.store,
                        CoreDemoRunContext.TASK_SPECIFICATION_ID,approval,CoreDemoBudgets.fixed(),
                        baseline,holder.loaded.manifest(),
                        new CoreDemoArtifactScope(runId,holder.loaded.manifestHash()),true);
                workflowResult=workflow.execute(context);
                validateIdentity(context,workflowResult);
                closeAttempted=true;
                workflow.close();
            }catch(Exception error){
                if(!closeAttempted)try{workflow.close();}catch(Exception closeError){
                    error.addSuppressed(closeError);
                }
                workflowResult=context==null
                        ? failureResult(runId,holder.loaded.manifest().scenarioId(),
                                prepared.workspace().workspaceIdentityHash(),error)
                        : failureResult(context,error);
            }
            closeQuietly(holder.store);
            holder.store=stores.open(holder.workspace);
            CoreDemoExitCode exit=map(workflowResult.terminalCategory());
            holder.store.coreDemoRuns.completeWorkflow(runId,workflowResult.successful(),
                    workflowResult.completedPhase().name(),workflowResult.finalTreeHash(),
                    workflowResult.repairCount(),
                    workflowResult.resume()==CoreDemoWorkflowResult.FactStatus.PASSED,
                    workflowResult.rewind()==CoreDemoWorkflowResult.FactStatus.PASSED,
                    workflowResult.boundedErrorCategory(),workflowResult.boundedErrorExcerpt());
            boolean retain=workflowResult.cleanupRecommendation()
                    ==CoreDemoWorkflowResult.CleanupRecommendation.RETAIN_FOR_DIAGNOSTICS
                    || exit!=CoreDemoExitCode.COMPLETED;
            CoreDemoRunResult terminal=writeTerminal(holder,baseline,workflowResult,exit,
                    workflowResult.terminalCategory().name(),retain);
            if(exit==CoreDemoExitCode.COMPLETED
                    && terminal.exitCode()==CoreDemoExitCode.COMPLETED){
                return cleanupAfterCommit(holder,terminal);
            }
            return terminal;
        }catch(Exception error){
            closeQuietly(holder.store);
            return result(runId,CoreDemoExitCode.INTERNAL_ERROR,"INTERNAL_ERROR",true,
                    "RUNNER_ERROR",error.getMessage());
        }finally{
            closeQuietly(holder.store);
        }
    }

    private CoreDemoRunResult writeTerminal(Holder holder,CoreDemoBaselineResult baseline,
            CoreDemoWorkflowResult workflowResult,CoreDemoExitCode exit,String status,
            boolean retain){
        boolean bundleCommitted=false;
        try{
            holder.store.coreDemoRuns.startArtifacts(holder.workspace.runId());
            CoreDemoArtifactInput input=new CoreDemoArtifactInput(holder.workspace.runId(),
                    holder.loaded.manifest(),holder.loaded.manifestHash(),baseline,workflowResult,
                    exit,status,retain?"RETAINED_FOR_DIAGNOSTICS":"PENDING_AFTER_COMMIT");
            artifacts.write(holder.workspace.artifacts(),input);
            bundleCommitted=true;
            afterArtifactHook.afterCommit();
            holder.store.coreDemoRuns.commitArtifacts(holder.workspace.runId(),exit.code());
            return result(holder.workspace.runId(),exit,status,retain,
                    workflowResult==null?null:workflowResult.boundedErrorCategory(),
                    workflowResult==null?null:workflowResult.boundedErrorExcerpt());
        }catch(Exception error){
            if(!bundleCommitted)try{holder.store.coreDemoRuns.failArtifacts(holder.workspace.runId(),
                    "ARTIFACT_COMMIT_FAILED",error.getMessage());}catch(Exception ignored){}
            if(bundleCommitted)return result(holder.workspace.runId(),CoreDemoExitCode.INTERNAL_ERROR,
                    "ARTIFACTS_COMMITTED_METADATA_RESIDUE",true,
                    "RUN_TERMINAL_PERSISTENCE_FAILED",error.getMessage());
            return result(holder.workspace.runId(),CoreDemoExitCode.ARTIFACT_FAILED,
                    "ARTIFACT_FAILED",true,"ARTIFACT_COMMIT_FAILED",error.getMessage());
        }
    }

    private CoreDemoRunResult cleanupAfterCommit(Holder holder,CoreDemoRunResult committed){
        String runId=holder.workspace.runId();
        try{
            holder.store.coreDemoRuns.markCleanupPending(runId);
            closeQuietly(holder.store);holder.store=null;
            workspaces.cleanupSuccessfulRuntime(holder.workspace);
            try(StateStore reopened=stores.open(holder.workspace)){
                reopened.coreDemoRuns.markCleaned(runId);
            }
            return result(runId,CoreDemoExitCode.COMPLETED,"COMPLETED",false,null,null);
        }catch(Exception error){
            closeQuietly(holder.store);holder.store=null;
            try(StateStore reopened=stores.open(holder.workspace)){
                reopened.coreDemoRuns.retainForDiagnostics(
                        runId,"CLEANUP_PARTIAL",error.getMessage());
            }catch(Exception ignored){}
            return result(runId,CoreDemoExitCode.INTERNAL_ERROR,
                    "CLEANUP_PENDING",true,"CLEANUP_PARTIAL",error.getMessage());
        }
    }

    private static void validateIdentity(CoreDemoRunContext c,CoreDemoWorkflowResult r){
        Objects.requireNonNull(r,"workflow result");
        if(!c.runId().equals(r.runId())||!c.scenarioId().equals(r.scenarioId())
                ||!c.workspaceIdentityHash().equals(r.workspaceIdentityHash()))
            throw new IllegalStateException("Workflow result identity mismatch");
    }
    private static CoreDemoWorkflowResult failureResult(CoreDemoRunContext c,Exception e){
        return failureResult(c.runId(),c.scenarioId(),c.workspaceIdentityHash(),e);
    }
    private static CoreDemoWorkflowResult failureResult(String runId,String scenarioId,
            String workspaceIdentityHash,Exception e){
        return new CoreDemoWorkflowResult(runId,scenarioId,workspaceIdentityHash,
                CoreDemoWorkflowResult.TerminalCategory.INTERNAL_ERROR,
                CoreDemoWorkflowResult.Phase.PLAN,
                CoreDemoWorkflowResult.FactStatus.FAILED,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,0,0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,List.of(),0,List.of(),0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                null,null,null,"WORKFLOW_EXCEPTION",bounded(e.getMessage()),
                CoreDemoWorkflowResult.CleanupRecommendation.RETAIN_FOR_DIAGNOSTICS);
    }
    private static CoreDemoExitCode map(CoreDemoWorkflowResult.TerminalCategory category){
        return switch(category){
            case COMPLETED->CoreDemoExitCode.COMPLETED;
            case PRIMARY_WORKFLOW_SUCCEEDED,PLAN_CODE_INTELLIGENCE_EXECUTION_FAILED->CoreDemoExitCode.EXECUTION_FAILED;
            case VERIFICATION_REPAIR_FAILED,DEMO_SCENARIO_DRIFT->CoreDemoExitCode.VERIFICATION_FAILED;
            case RESUME_REWIND_FAILED->CoreDemoExitCode.RESUME_REWIND_FAILED;
            case INTERNAL_ERROR->CoreDemoExitCode.INTERNAL_ERROR;
        };
    }
    private static CoreDemoBaselineResult fallbackBaseline(){
        return new CoreDemoBaselineResult(CoreDemoBaselineResult.Status.BASELINE_ERROR,null,0,
                0,0,0,0,null,null,"Baseline evidence unavailable",false,true,false,null);
    }
    private static CoreDemoRunResult result(String runId,CoreDemoExitCode exit,String status,
            boolean retained,String category,String error){
        return new CoreDemoRunResult(runId,SCENARIO,status,exit,
                "~/.paicli/demo-runs/"+runId+"/artifacts/final",retained,category,bounded(error));
    }
    private static String bounded(String value){
        if(value==null)return null;String s=value.replace('\r',' ').replace('\n',' ').replace('\0',' ');
        return s.substring(0,Math.min(2048,s.length()));
    }
    private static void closeQuietly(StateStore store){
        if(store!=null)try{store.close();}catch(Exception ignored){}
    }
    @FunctionalInterface
    interface AfterArtifactHook { void afterCommit() throws Exception; }

    private static final class Holder{
        CoreDemoWorkspace workspace;
        CoreDemoFixtureManifestLoader.LoadedManifest loaded;
        StateStore store;
    }
}
