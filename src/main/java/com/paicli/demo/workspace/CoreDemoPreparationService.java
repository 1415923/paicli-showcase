package com.paicli.demo.workspace;

import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.baseline.CoreDemoBaselineService;
import com.paicli.demo.fixture.*;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns the one-shot R1 preparation lifecycle; it never invokes a provider or AgentLoop. */
public final class CoreDemoPreparationService implements CoreDemoPreparationGateway {
    private final CoreDemoFixtureManifestLoader loader;
    private final CoreDemoFixtureExtractor extractor;
    private final CoreDemoWorkspaceService workspaces;
    private final CoreDemoBaselineService baseline;
    private final ConcurrentHashMap<String, CoreDemoPreparation> terminal = new ConcurrentHashMap<>();

    public CoreDemoPreparationService(CoreDemoFixtureManifestLoader loader,
            CoreDemoWorkspaceService workspaces, CoreDemoBaselineService baseline) {
        this.loader=Objects.requireNonNull(loader); this.extractor=new CoreDemoFixtureExtractor(loader);
        this.workspaces=Objects.requireNonNull(workspaces); this.baseline=Objects.requireNonNull(baseline);
    }

    public CoreDemoPreparation prepare() throws CoreDemoFixtureException, IOException {
        String runId="run-"+UUID.randomUUID().toString().replace("-","").substring(0,16).toLowerCase(Locale.ROOT);
        return prepareOwned(runId);
    }

    CoreDemoPreparation prepareOwned(String runId) throws CoreDemoFixtureException, IOException {
        return prepareForRun(runId, PreparationObserver.NONE);
    }

    public CoreDemoPreparation prepareForRun(String runId, PreparationObserver observer)
            throws CoreDemoFixtureException, IOException {
        Objects.requireNonNull(observer, "observer");
        CoreDemoPreparation existing=terminal.get(runId);
        if(existing!=null) return existing;
        var loaded=loader.load();
        CoreDemoWorkspace run=workspaces.allocate(runId,loaded.manifest().scenarioId(),
                loaded.manifestHash(),loaded.manifest().expectedBaselineTreeHash());
        try {
            observer.allocated(run, loaded);
            run=run.withStatus(CoreDemoWorkspaceStatus.EXTRACTING);
            observer.phase(run, "EXTRACTION");
            extractor.extract(run.workspace(),loaded);
            run=run.withStatus(CoreDemoWorkspaceStatus.VALIDATING_SEED);
            run=run.withStatus(CoreDemoWorkspaceStatus.BASELINE_RUNNING);
            observer.phase(run, "BASELINE");
            CoreDemoBaselineResult result=baseline.verify(run.workspace(),loaded.manifest());
            CoreDemoWorkspaceStatus status=result.status()==CoreDemoBaselineResult.Status.BASELINE_PASSED
                    ? CoreDemoWorkspaceStatus.BASELINE_PASSED : CoreDemoWorkspaceStatus.BASELINE_FAILED;
            CoreDemoPreparation prepared=new CoreDemoPreparation(run.withStatus(status),loaded,result);
            terminal.putIfAbsent(runId,prepared);
            return terminal.get(runId);
        } catch(Exception error) {
            CoreDemoPreparation failed=new CoreDemoPreparation(
                    workspaces.retainForDiagnostics(run.withStatus(CoreDemoWorkspaceStatus.PREPARATION_FAILED)),
                    loaded,null);
            terminal.putIfAbsent(runId,failed);
            if(error instanceof CoreDemoFixtureException fixture) throw fixture;
            if(error instanceof IOException io) throw io;
            throw new CoreDemoFixtureException("Demo preparation failed",error);
        }
    }

    public interface PreparationObserver {
        PreparationObserver NONE = new PreparationObserver() { };
        default void allocated(CoreDemoWorkspace workspace,
                CoreDemoFixtureManifestLoader.LoadedManifest manifest) throws Exception { }
        default void phase(CoreDemoWorkspace workspace, String phase) throws Exception { }
    }

    public record CoreDemoPreparation(CoreDemoWorkspace workspace,
            CoreDemoFixtureManifestLoader.LoadedManifest manifest,
            CoreDemoBaselineResult baselineResult) {
        public boolean readyForHeadlessRunner() {
            return workspace.status()==CoreDemoWorkspaceStatus.BASELINE_PASSED
                    && baselineResult!=null
                    && baselineResult.status()==CoreDemoBaselineResult.Status.BASELINE_PASSED;
        }
    }
}
