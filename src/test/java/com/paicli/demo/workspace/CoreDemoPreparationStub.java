package com.paicli.demo.workspace;

import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.fixture.*;
import java.nio.file.Path;

/** Test-only preparation seam; it never executes Maven or production workflow. */
public final class CoreDemoPreparationStub implements CoreDemoPreparationGateway {
    private final CoreDemoWorkspaceService workspaces;
    private final CoreDemoBaselineResult.Status status;
    public CoreDemoWorkspace lastWorkspace;

    public CoreDemoPreparationStub(CoreDemoWorkspaceService workspaces,
            CoreDemoBaselineResult.Status status){
        this.workspaces=workspaces;this.status=status;
    }

    @Override public CoreDemoPreparationService.CoreDemoPreparation prepareForRun(
            String runId,CoreDemoPreparationService.PreparationObserver observer)
            throws CoreDemoFixtureException,java.io.IOException{
        var loader=new CoreDemoFixtureManifestLoader();var loaded=loader.load();
        CoreDemoWorkspace run=workspaces.allocate(runId,loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        lastWorkspace=run;
        try{
            observer.allocated(run,loaded);
            run=run.withStatus(CoreDemoWorkspaceStatus.EXTRACTING);observer.phase(run,"EXTRACTION");
            new CoreDemoFixtureExtractor(loader).extract(run.workspace(),loaded);
            run=run.withStatus(CoreDemoWorkspaceStatus.BASELINE_RUNNING);observer.phase(run,"BASELINE");
        }catch(CoreDemoFixtureException|java.io.IOException e){throw e;}
        catch(Exception e){throw new CoreDemoFixtureException("Observer failure",e);}
        String hash=loaded.manifest().expectedBaselineTreeHash();
        boolean passed=status==CoreDemoBaselineResult.Status.BASELINE_PASSED;
        var baseline=new CoreDemoBaselineResult(status,passed?0:1,5,
                passed?3:0,passed?0:1,0,0,"a".repeat(64),"b".repeat(64),
                passed?null:"baseline failed",false,true,true,hash);
        run=run.withStatus(passed?CoreDemoWorkspaceStatus.BASELINE_PASSED:
                CoreDemoWorkspaceStatus.BASELINE_FAILED);
        lastWorkspace=run;
        return new CoreDemoPreparationService.CoreDemoPreparation(run,loaded,baseline);
    }
}
